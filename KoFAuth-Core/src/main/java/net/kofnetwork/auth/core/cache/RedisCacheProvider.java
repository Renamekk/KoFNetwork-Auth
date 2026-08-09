package net.kofnetwork.auth.core.cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.exception.CacheUnavailableException;
import net.kofnetwork.auth.api.exception.ConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Кэш и межпроцессная шина на Redis (Lettuce).
 *
 * <p><b>Отказ Redis не роняет аутентификацию.</b> Каждая операция обёрнута так, что
 * исключение превращается в «нет данных» и запись в лог, а не всплывает вызывающему.
 * Контракт {@link CacheProvider} это допускает: для вызывающего промах кэша и отказ
 * кэша — одно событие, после которого он идёт в MySQL.
 *
 * <p><b>Скользящее окно на Lua.</b> Ограничение скорости считается упорядоченным
 * множеством меток времени, а чистка старых, добавление новой и подсчёт выполняются
 * одним скриптом. Тремя отдельными командами это было бы неатомарно: параллельные
 * запросы успевали бы посчитать себя до того, как их учли, и лимит превышался бы.
 */
public final class RedisCacheProvider implements CacheProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisCacheProvider.class);

    /**
     * Чистка окна, добавление события и подсчёт — одной атомарной операцией.
     * KEYS[1] — ключ, ARGV[1] — текущее время в мс, ARGV[2] — окно в мс, ARGV[3] — метка события.
     */
    private static final String SLIDING_WINDOW_ADD = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            redis.call('ZADD', key, now, ARGV[3])
            redis.call('PEXPIRE', key, window)
            return redis.call('ZCARD', key)
            """;

    /** То же без добавления события: только чистка и подсчёт. */
    private static final String SLIDING_WINDOW_COUNT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            return redis.call('ZCARD', key)
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> commands;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final String prefix;

    private final List<RedisPubSubAdapter<String, String>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private RedisCacheProvider(RedisClient client,
                               StatefulRedisConnection<String, String> connection,
                               StatefulRedisPubSubConnection<String, String> pubSubConnection,
                               String prefix) {
        this.client = client;
        this.connection = connection;
        this.commands = connection.async();
        this.pubSubConnection = pubSubConnection;
        this.prefix = prefix;
    }

    /**
     * Подключается к Redis по конфигурации.
     *
     * @throws ConfigurationException если подключиться не удалось
     */
    public static @NotNull RedisCacheProvider connect(@NotNull ConfigurationService config) {
        String host = config.getString(ConfigFile.DATABASE, "redis.host", "localhost");
        int port = config.getInt(ConfigFile.DATABASE, "redis.port", 6379);
        String password = config.getString(ConfigFile.DATABASE, "redis.password", "");
        int database = config.getInt(ConfigFile.DATABASE, "redis.database", 0);
        Duration timeout = config.getDuration(ConfigFile.DATABASE, "redis.timeout", Duration.ofSeconds(3));
        String prefix = config.getString(ConfigFile.DATABASE, "redis.key-prefix", "kofauth:");

        RedisURI.Builder uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(database)
                .withTimeout(timeout);
        if (!password.isBlank()) {
            uri.withPassword(password.toCharArray());
        }

        RedisClient client = RedisClient.create(uri.build());
        try {
            StatefulRedisConnection<String, String> connection = client.connect();
            StatefulRedisPubSubConnection<String, String> pubSub = client.connectPubSub();
            LOGGER.info("Подключение к Redis установлено: {}:{} (база {}, префикс '{}')",
                    host, port, database, prefix);
            return new RedisCacheProvider(client, connection, pubSub, prefix);
        } catch (RuntimeException e) {
            client.shutdown();
            throw new ConfigurationException(
                    "Не удалось подключиться к Redis " + host + ":" + port
                            + ". Отключите его в database.yml (redis.enabled: false), "
                            + "если он не используется.", e);
        }
    }

    private String key(String raw) {
        return prefix + raw;
    }

    /**
     * Выполняет операцию, подменяя отказ Redis значением по умолчанию.
     *
     * <p>Здесь сосредоточена вся политика деградации: ни один вызывающий не должен
     * оборачивать обращения к кэшу в {@code try-catch}.
     */
    private <T> CompletableFuture<T> guarded(Supplier<CompletableFuture<T>> operation,
                                             T fallback,
                                             String description) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(fallback);
        }
        try {
            return operation.get().exceptionally(e -> {
                LOGGER.warn("Redis недоступен при операции '{}': {}. Работаем без кэша.",
                        description, e.getMessage());
                return fallback;
            });
        } catch (RuntimeException e) {
            LOGGER.warn("Redis недоступен при операции '{}': {}. Работаем без кэша.",
                    description, e.getMessage());
            return CompletableFuture.completedFuture(fallback);
        }
    }

    /**
     * Выполняет операцию, не пряча отказ Redis.
     *
     * <p>Зеркало {@link #guarded}: там отказ подменяется значением по умолчанию, здесь
     * поднимается вызывающему. Разница нужна потому, что «ключа нет» и «сервер не
     * ответил» — разные события для состояния входа, привязок и счётчиков лимитов,
     * у которых нет копии в MySQL.
     */
    private <T> CompletableFuture<T> strict(Supplier<CompletableFuture<T>> operation,
                                            String description) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new CacheUnavailableException(
                    "Соединение с Redis закрыто (" + description + ")"));
        }
        try {
            return operation.get().handle((value, failure) -> {
                if (failure == null) {
                    return CompletableFuture.completedFuture(value);
                }
                LOGGER.error("Redis не ответил на критичную операцию '{}': {}",
                        description, failure.toString());
                return CompletableFuture.<T>failedFuture(new CacheUnavailableException(
                        "Redis недоступен: " + description, failure));
            }).thenCompose(java.util.function.Function.identity());
        } catch (RuntimeException e) {
            LOGGER.error("Redis не ответил на критичную операцию '{}': {}", description, e.toString());
            return CompletableFuture.failedFuture(new CacheUnavailableException(
                    "Redis недоступен: " + description, e));
        }
    }

    @Override
    public @NotNull Critical critical() {
        return new Critical() {

            @Override
            public @NotNull CompletableFuture<Optional<String>> get(@NotNull String key) {
                return strict(() -> commands.get(key(key)).toCompletableFuture()
                        .thenApply(Optional::ofNullable), "get " + key);
            }

            @Override
            public @NotNull CompletableFuture<Void> set(@NotNull String key,
                                                        @NotNull String value,
                                                        @NotNull Duration ttl) {
                return strict(() -> commands.psetex(key(key), ttl.toMillis(), value)
                        .toCompletableFuture().thenApply(ignored -> (Void) null), "set " + key);
            }

            @Override
            public @NotNull CompletableFuture<Boolean> delete(@NotNull String key) {
                return strict(() -> commands.del(key(key)).toCompletableFuture()
                        .thenApply(count -> count > 0), "delete " + key);
            }

            @Override
            public @NotNull CompletableFuture<Map<String, String>> getHash(@NotNull String key) {
                return strict(() -> commands.hgetall(key(key)).toCompletableFuture()
                        .thenApply(map -> map == null ? Map.<String, String>of() : map),
                        "getHash " + key);
            }

            @Override
            public @NotNull CompletableFuture<Void> setHash(@NotNull String key,
                                                            @NotNull Map<String, String> values,
                                                            @NotNull Duration ttl) {
                if (values.isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }
                String prefixed = key(key);
                return strict(() -> commands.hset(prefixed, values).toCompletableFuture()
                                .thenCompose(ignored -> commands.pexpire(prefixed, ttl.toMillis())
                                        .toCompletableFuture())
                                .thenApply(ignored -> (Void) null),
                        "setHash " + key);
            }

            @Override
            public @NotNull CompletableFuture<Long> incrementSlidingWindow(@NotNull String key,
                                                                            @NotNull Duration window) {
                long now = System.currentTimeMillis();
                String member = now + "-" + UUID.randomUUID();
                return strict(() -> commands.<Long>eval(SLIDING_WINDOW_ADD,
                                ScriptOutputType.INTEGER, new String[]{key(key)},
                                String.valueOf(now), String.valueOf(window.toMillis()), member)
                        .toCompletableFuture(), "slidingWindow " + key);
            }
        };
    }

    // ------------------------------------------------------------------ строки

    @Override
    public @NotNull CompletableFuture<Optional<String>> get(@NotNull String key) {
        return guarded(() -> commands.get(key(key)).toCompletableFuture().thenApply(Optional::ofNullable),
                Optional.empty(), "get " + key);
    }

    @Override
    public @NotNull CompletableFuture<Void> set(@NotNull String key,
                                                @NotNull String value,
                                                @NotNull Duration ttl) {
        return guarded(() -> commands.psetex(key(key), ttl.toMillis(), value)
                        .toCompletableFuture().thenApply(ignored -> (Void) null),
                null, "set " + key);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> setIfAbsent(@NotNull String key,
                                                            @NotNull String value,
                                                            @NotNull Duration ttl) {
        return guarded(() -> commands.set(key(key), value, SetArgs.Builder.nx().px(ttl.toMillis()))
                        .toCompletableFuture().thenApply("OK"::equals),
                // При отказе Redis отвечаем «не удалось занять»: выдать ложное
                // подтверждение блокировки опаснее, чем отказать в операции.
                false, "setIfAbsent " + key);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull String key) {
        return guarded(() -> commands.del(key(key)).toCompletableFuture().thenApply(count -> count > 0),
                false, "delete " + key);
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> getAndDelete(@NotNull String key) {
        // GETDEL появился в Redis 6.2. На более старых версиях операция не атомарна,
        // и одноразовые токены перестают быть одноразовыми.
        return guarded(() -> commands.getdel(key(key)).toCompletableFuture().thenApply(Optional::ofNullable),
                Optional.empty(), "getAndDelete " + key);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> exists(@NotNull String key) {
        return guarded(() -> commands.exists(key(key)).toCompletableFuture().thenApply(count -> count > 0),
                false, "exists " + key);
    }

    // ------------------------------------------------------------------ хэши

    @Override
    public @NotNull CompletableFuture<Map<String, String>> getHash(@NotNull String key) {
        return guarded(() -> commands.hgetall(key(key)).toCompletableFuture()
                        .thenApply(map -> map == null ? Map.<String, String>of() : map),
                Map.of(), "getHash " + key);
    }

    @Override
    public @NotNull CompletableFuture<Void> setHash(@NotNull String key,
                                                    @NotNull Map<String, String> values,
                                                    @NotNull Duration ttl) {
        if (values.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String prefixed = key(key);
        return guarded(() -> commands.hset(prefixed, values).toCompletableFuture()
                        .thenCompose(ignored -> commands.pexpire(prefixed, ttl.toMillis()).toCompletableFuture())
                        .thenApply(ignored -> (Void) null),
                null, "setHash " + key);
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> getHashField(@NotNull String key,
                                                                      @NotNull String field) {
        return guarded(() -> commands.hget(key(key), field).toCompletableFuture().thenApply(Optional::ofNullable),
                Optional.empty(), "getHashField " + key);
    }

    // ------------------------------------------------------------------ счётчики

    @Override
    public @NotNull CompletableFuture<Long> increment(@NotNull String key,
                                                       @NotNull Duration ttlOnCreate) {
        String prefixed = key(key);
        return guarded(() -> commands.incr(prefixed).toCompletableFuture()
                        .thenCompose(value -> {
                            // Срок жизни задаётся только при создании ключа, иначе окно
                            // продлевалось бы каждым событием и никогда не закрывалось.
                            if (value == 1L) {
                                return commands.pexpire(prefixed, ttlOnCreate.toMillis())
                                        .toCompletableFuture().thenApply(ignored -> value);
                            }
                            return CompletableFuture.completedFuture(value);
                        }),
                1L, "increment " + key);
    }

    @Override
    public @NotNull CompletableFuture<Long> incrementSlidingWindow(@NotNull String key,
                                                                    @NotNull Duration window) {
        long now = System.currentTimeMillis();
        // Метка уникальна: два события в одну миллисекунду не должны слиться в одно.
        String member = now + "-" + UUID.randomUUID();
        return guarded(() -> commands.<Long>eval(SLIDING_WINDOW_ADD, ScriptOutputType.INTEGER,
                                new String[]{key(key)},
                                String.valueOf(now), String.valueOf(window.toMillis()), member)
                        .toCompletableFuture(),
                1L, "slidingWindow " + key);
    }

    @Override
    public @NotNull CompletableFuture<Long> countSlidingWindow(@NotNull String key,
                                                                @NotNull Duration window) {
        long now = System.currentTimeMillis();
        return guarded(() -> commands.<Long>eval(SLIDING_WINDOW_COUNT, ScriptOutputType.INTEGER,
                                new String[]{key(key)},
                                String.valueOf(now), String.valueOf(window.toMillis()))
                        .toCompletableFuture(),
                0L, "countSlidingWindow " + key);
    }

    // ------------------------------------------------------------------ обслуживание

    @Override
    public @NotNull CompletableFuture<List<String>> keys(@NotNull String pattern) {
        return guarded(() -> commands.keys(key(pattern)).toCompletableFuture()
                        .thenApply(found -> {
                            List<String> stripped = new ArrayList<>(found.size());
                            for (String full : found) {
                                stripped.add(full.startsWith(prefix) ? full.substring(prefix.length()) : full);
                            }
                            return stripped;
                        }),
                List.of(), "keys " + pattern);
    }

    @Override
    public @NotNull CompletableFuture<Long> deleteByPattern(@NotNull String pattern) {
        return keys(pattern).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(0L);
            }
            String[] prefixed = found.stream().map(this::key).toArray(String[]::new);
            return guarded(() -> commands.del(prefixed).toCompletableFuture(), 0L, "deleteByPattern");
        });
    }

    // ------------------------------------------------------------------ Pub/Sub

    @Override
    public @NotNull CompletableFuture<Long> publish(@NotNull String channel, @NotNull String message) {
        return guarded(() -> commands.publish(key(channel), message).toCompletableFuture(),
                0L, "publish " + channel);
    }

    @Override
    public @NotNull Subscription subscribe(@NotNull String channel, @NotNull Consumer<String> handler) {
        String prefixed = key(channel);

        RedisPubSubAdapter<String, String> adapter = new RedisPubSubAdapter<>() {
            @Override
            public void message(String receivedChannel, String message) {
                if (!prefixed.equals(receivedChannel)) {
                    return;
                }
                try {
                    handler.accept(message);
                } catch (RuntimeException e) {
                    // Исключение в обработчике не должно останавливать доставку
                    // последующих сообщений всем остальным подписчикам.
                    LOGGER.error("Ошибка в обработчике канала {}", receivedChannel, e);
                }
            }
        };

        pubSubConnection.addListener(adapter);
        listeners.add(adapter);
        pubSubConnection.sync().subscribe(prefixed);

        return () -> {
            pubSubConnection.removeListener(adapter);
            listeners.remove(adapter);
            try {
                pubSubConnection.sync().unsubscribe(prefixed);
            } catch (RuntimeException e) {
                LOGGER.debug("Не удалось отписаться от канала {}", prefixed, e);
            }
        };
    }

    // ------------------------------------------------------------------ состояние

    @Override
    public boolean isAvailable() {
        return !closed.get() && connection.isOpen();
    }

    @Override
    public @NotNull String providerName() {
        return "redis";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        listeners.forEach(pubSubConnection::removeListener);
        listeners.clear();
        try {
            pubSubConnection.close();
            connection.close();
            client.shutdown();
            LOGGER.info("Подключение к Redis закрыто");
        } catch (RuntimeException e) {
            LOGGER.warn("Ошибка при закрытии подключения к Redis", e);
        }
    }
}
