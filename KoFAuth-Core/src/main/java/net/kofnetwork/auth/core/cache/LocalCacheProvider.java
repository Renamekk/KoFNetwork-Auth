package net.kofnetwork.auth.core.cache;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Хранилище состояния в памяти процесса.
 *
 * <p><b>Зачем оно нужно рядом с {@link NoopCacheProvider}.</b> Раньше отключённый в
 * конфигурации Redis и упавший Redis приводили к одному и тому же: подставлялась
 * заглушка, которая на всё отвечала «нет данных». Из-за этого нельзя было отличить
 * осознанный выбор от аварии, и оба случая обслуживались одинаково — небезопасно.
 *
 * <p>Теперь это два разных решения. Осознанно выключенный Redis даёт вот эту
 * реализацию: она честно хранит состояние машины входа, привязки и счётчики лимитов,
 * поэтому сеть из одного прокси и одного бэкенда работает полностью — включая
 * ограничение скорости, которое с заглушкой попросту не считалось. Недоступный Redis
 * при {@code redis.enabled: true} — авария, и она обрабатывается как авария.
 *
 * <p>Единственное, чего здесь нет, — общей памяти между процессами:
 * {@link #isDistributed()} возвращает {@code false}, и подписчик межпроцессной шины по
 * этому признаку знает, что соседей не услышит.
 */
public final class LocalCacheProvider implements CacheProvider {

    /** Значение со сроком жизни. */
    private record Entry(Object value, Instant expiresAt) {

        boolean isAlive(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Метки времени скользящих окон. Ключ — тот же, что у обычных значений. */
    private final Map<String, List<Long>> windows = new ConcurrentHashMap<>();

    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    private volatile boolean closed;

    // ------------------------------------------------------------------ строки

    @Override
    public @NotNull CompletableFuture<Optional<String>> get(@NotNull String key) {
        return CompletableFuture.completedFuture(read(key, String.class));
    }

    @Override
    public @NotNull CompletableFuture<Void> set(@NotNull String key,
                                                @NotNull String value,
                                                @NotNull Duration ttl) {
        entries.put(key, new Entry(value, Instant.now().plus(ttl)));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> setIfAbsent(@NotNull String key,
                                                            @NotNull String value,
                                                            @NotNull Duration ttl) {
        Instant now = Instant.now();
        Entry created = new Entry(value, now.plus(ttl));
        // merge вместо putIfAbsent: истёкшая запись обязана уступить место новой,
        // иначе однажды занятый ключ блокировал бы себя навсегда.
        Entry current = entries.merge(key, created,
                (existing, fresh) -> existing.isAlive(now) ? existing : fresh);
        return CompletableFuture.completedFuture(current == created);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull String key) {
        windows.remove(key);
        return CompletableFuture.completedFuture(entries.remove(key) != null);
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> getAndDelete(@NotNull String key) {
        Entry removed = entries.remove(key);
        return CompletableFuture.completedFuture(alive(removed)
                ? Optional.of(String.valueOf(removed.value()))
                : Optional.empty());
    }

    @Override
    public @NotNull CompletableFuture<Boolean> exists(@NotNull String key) {
        return CompletableFuture.completedFuture(alive(entries.get(key)));
    }

    // ------------------------------------------------------------------ хэши

    @SuppressWarnings("unchecked")
    @Override
    public @NotNull CompletableFuture<Map<String, String>> getHash(@NotNull String key) {
        Entry entry = entries.get(key);
        if (!alive(entry) || !(entry.value() instanceof Map<?, ?> map)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return CompletableFuture.completedFuture(Map.copyOf((Map<String, String>) map));
    }

    @Override
    public @NotNull CompletableFuture<Void> setHash(@NotNull String key,
                                                    @NotNull Map<String, String> values,
                                                    @NotNull Duration ttl) {
        entries.put(key, new Entry(new LinkedHashMap<>(values), Instant.now().plus(ttl)));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> getHashField(@NotNull String key,
                                                                      @NotNull String field) {
        return getHash(key).thenApply(hash -> Optional.ofNullable(hash.get(field)));
    }

    // ------------------------------------------------------------------ счётчики

    @Override
    public @NotNull CompletableFuture<Long> increment(@NotNull String key,
                                                       @NotNull Duration ttlOnCreate) {
        Instant now = Instant.now();
        Entry updated = entries.compute(key, (ignored, existing) -> {
            long previous = alive(existing) && existing.value() instanceof Long value ? value : 0L;
            Instant expiry = alive(existing) ? existing.expiresAt() : now.plus(ttlOnCreate);
            return new Entry(previous + 1, expiry);
        });
        return CompletableFuture.completedFuture((Long) updated.value());
    }

    @Override
    public @NotNull CompletableFuture<Long> incrementSlidingWindow(@NotNull String key,
                                                                    @NotNull Duration window) {
        return CompletableFuture.completedFuture(slide(key, window, true));
    }

    @Override
    public @NotNull CompletableFuture<Long> countSlidingWindow(@NotNull String key,
                                                                @NotNull Duration window) {
        return CompletableFuture.completedFuture(slide(key, window, false));
    }

    /** Чистка окна, добавление события и подсчёт — под одной блокировкой на ключ. */
    private long slide(String key, Duration window, boolean add) {
        long now = System.currentTimeMillis();
        long cutoff = now - window.toMillis();
        List<Long> stamps = windows.computeIfAbsent(key, ignored -> new ArrayList<>());
        synchronized (stamps) {
            stamps.removeIf(stamp -> stamp <= cutoff);
            if (add) {
                stamps.add(now);
            }
            return stamps.size();
        }
    }

    // ------------------------------------------------------------------ обслуживание

    @Override
    public @NotNull CompletableFuture<List<String>> keys(@NotNull String pattern) {
        Pattern compiled = glob(pattern);
        Instant now = Instant.now();
        return CompletableFuture.completedFuture(entries.entrySet().stream()
                .filter(entry -> entry.getValue().isAlive(now))
                .map(Map.Entry::getKey)
                .filter(key -> compiled.matcher(key).matches())
                .toList());
    }

    @Override
    public @NotNull CompletableFuture<Long> deleteByPattern(@NotNull String pattern) {
        return keys(pattern).thenApply(matched -> {
            matched.forEach(entries::remove);
            matched.forEach(windows::remove);
            return (long) matched.size();
        });
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char symbol : pattern.toCharArray()) {
            switch (symbol) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(symbol)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    // ------------------------------------------------------------------ Pub/Sub

    /**
     * Раздача внутри процесса.
     *
     * <p>Подписчик в этом же процессе сообщения получит; соседние узлы — нет, и
     * {@link #isDistributed()} об этом честно предупреждает.
     */
    @Override
    public @NotNull CompletableFuture<Long> publish(@NotNull String channel,
                                                     @NotNull String message) {
        List<Consumer<String>> handlers = subscribers.getOrDefault(channel, List.of());
        handlers.forEach(handler -> handler.accept(message));
        return CompletableFuture.completedFuture((long) handlers.size());
    }

    @Override
    public @NotNull Subscription subscribe(@NotNull String channel,
                                           @NotNull Consumer<String> handler) {
        List<Consumer<String>> handlers =
                subscribers.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>());
        handlers.add(handler);
        return () -> handlers.remove(handler);
    }

    // ------------------------------------------------------------------ состояние

    @Override
    public boolean isAvailable() {
        return !closed;
    }

    @Override
    public boolean isDistributed() {
        return false;
    }

    /**
     * Строгие операции совпадают с обычными.
     *
     * <p>Хранилище в памяти отказать не может: пока процесс жив, оно отвечает. Отличие
     * от Redis-реализации именно в этом — там строгий вызов обязан различать «ключа нет»
     * и «сервер не ответил».
     */
    @Override
    public @NotNull Critical critical() {
        return new Critical() {

            @Override
            public @NotNull CompletableFuture<Optional<String>> get(@NotNull String key) {
                return LocalCacheProvider.this.get(key);
            }

            @Override
            public @NotNull CompletableFuture<Void> set(@NotNull String key,
                                                        @NotNull String value,
                                                        @NotNull Duration ttl) {
                return LocalCacheProvider.this.set(key, value, ttl);
            }

            @Override
            public @NotNull CompletableFuture<Boolean> delete(@NotNull String key) {
                return LocalCacheProvider.this.delete(key);
            }

            @Override
            public @NotNull CompletableFuture<Map<String, String>> getHash(@NotNull String key) {
                return LocalCacheProvider.this.getHash(key);
            }

            @Override
            public @NotNull CompletableFuture<Void> setHash(@NotNull String key,
                                                            @NotNull Map<String, String> values,
                                                            @NotNull Duration ttl) {
                return LocalCacheProvider.this.setHash(key, values, ttl);
            }

            @Override
            public @NotNull CompletableFuture<Long> incrementSlidingWindow(@NotNull String key,
                                                                            @NotNull Duration window) {
                return LocalCacheProvider.this.incrementSlidingWindow(key, window);
            }
        };
    }

    @Override
    public @NotNull String providerName() {
        return "in-memory";
    }

    @Override
    public void close() {
        closed = true;
        entries.clear();
        windows.clear();
        subscribers.clear();
    }

    // ------------------------------------------------------------------ внутреннее

    private <T> Optional<T> read(String key, Class<T> type) {
        Entry entry = entries.get(key);
        if (!alive(entry)) {
            // Истёкшую запись убираем при чтении: отдельного потока чистки здесь нет,
            // а без удаления карта росла бы на каждый ушедший ключ.
            entries.remove(key, entry);
            return Optional.empty();
        }
        return type.isInstance(entry.value()) ? Optional.of(type.cast(entry.value())) : Optional.empty();
    }

    private static boolean alive(Entry entry) {
        return entry != null && entry.isAlive(Instant.now());
    }
}
