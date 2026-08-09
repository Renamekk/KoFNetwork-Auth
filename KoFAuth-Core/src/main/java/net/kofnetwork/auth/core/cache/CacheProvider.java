package net.kofnetwork.auth.core.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Кэш и межпроцессная шина.
 *
 * <p>Абстракция нужна не ради «подменяемости хранилища вообще», а ради одного
 * конкретного сценария: Redis может быть выключен в конфигурации или упасть в
 * работе, и KoFAuth обязан продолжить обслуживать вход, потеряв лишь скорость.
 * Эту роль играет {@link NoopCacheProvider}, который на все чтения отвечает
 * «нет данных», а на записи молчит.
 *
 * <p>Поэтому у методов чтения нет способа сообщить об ошибке: промах кэша и отказ
 * кэша для вызывающего — одно и то же событие, после которого он идёт в MySQL.
 */
public interface CacheProvider extends AutoCloseable {

    /** Значение по ключу. */
    @NotNull CompletableFuture<Optional<String>> get(@NotNull String key);

    /** Записывает значение со сроком жизни. */
    @NotNull CompletableFuture<Void> set(@NotNull String key, @NotNull String value, @NotNull Duration ttl);

    /**
     * Записывает, только если ключа ещё нет.
     *
     * @return {@code true}, если запись выполнена именно этим вызовом.
     *         Основа для распределённой блокировки и для одноразовых nonce
     */
    @NotNull CompletableFuture<Boolean> setIfAbsent(@NotNull String key,
                                                     @NotNull String value,
                                                     @NotNull Duration ttl);

    /** Удаляет ключ. */
    @NotNull CompletableFuture<Boolean> delete(@NotNull String key);

    /**
     * Атомарно читает и удаляет значение.
     *
     * <p>Ключевая операция для одноразовых токенов подтверждения: раздельные
     * «прочитать» и «удалить» дают окно, в котором двойное нажатие кнопки
     * «Подтвердить» в Telegram создаст две сессии.
     */
    @NotNull CompletableFuture<Optional<String>> getAndDelete(@NotNull String key);

    /** Есть ли ключ. */
    @NotNull CompletableFuture<Boolean> exists(@NotNull String key);

    /** Хэш целиком. Пустое отображение, если ключа нет. */
    @NotNull CompletableFuture<Map<String, String>> getHash(@NotNull String key);

    /** Записывает хэш со сроком жизни. */
    @NotNull CompletableFuture<Void> setHash(@NotNull String key,
                                             @NotNull Map<String, String> values,
                                             @NotNull Duration ttl);

    /** Одно поле хэша. */
    @NotNull CompletableFuture<Optional<String>> getHashField(@NotNull String key, @NotNull String field);

    /**
     * Увеличивает счётчик и, если ключ создан этим вызовом, задаёт ему срок жизни.
     *
     * @return значение после увеличения
     */
    @NotNull CompletableFuture<Long> increment(@NotNull String key, @NotNull Duration ttlOnCreate);

    /**
     * Считает события в скользящем окне.
     *
     * <p>Отдельная операция, а не {@link #increment}, потому что счётчик с фиксированным
     * окном позволяет обойти лимит: при ограничении «10 в минуту» можно сделать 10
     * запросов в конце одной минуты и ещё 10 в начале следующей. Реализация ведёт
     * упорядоченное множество меток времени и отбрасывает вышедшие за окно.
     *
     * @return число событий в окне с учётом текущего
     */
    @NotNull CompletableFuture<Long> incrementSlidingWindow(@NotNull String key, @NotNull Duration window);

    /** Число событий в окне без добавления нового. */
    @NotNull CompletableFuture<Long> countSlidingWindow(@NotNull String key, @NotNull Duration window);

    /** Удаляет ключи по шаблону. Дорогая операция, только для обслуживания. */
    @NotNull CompletableFuture<Long> deleteByPattern(@NotNull String pattern);

    // ------------------------------------------------------------------ Pub/Sub

    /**
     * Публикует сообщение в канал.
     *
     * @return число получателей; {@code 0} при отключённом кэше
     */
    @NotNull CompletableFuture<Long> publish(@NotNull String channel, @NotNull String message);

    /**
     * Подписывается на канал.
     *
     * <p>Обработчик вызывается в потоке подписчика. Тяжёлую работу из него следует
     * уводить в пул: заблокировав этот поток, можно остановить доставку всех
     * последующих сообщений.
     *
     * @return дескриптор для отписки
     */
    @NotNull Subscription subscribe(@NotNull String channel, @NotNull Consumer<String> handler);

    /** Дескриптор подписки. */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    // ------------------------------------------------------------------ состояние

    /**
     * Работает ли кэш прямо сейчас.
     *
     * <p>{@code false} — не ошибка: это либо отключённый в конфигурации Redis,
     * либо временно недоступный. Вызывающий в обоих случаях идёт в MySQL.
     */
    boolean isAvailable();

    /** Имя реализации для {@code /auth info}. */
    @NotNull String providerName();

    @Override
    void close();

    /** Ключи с общим префиксом из конфигурации. */
    final class Keys {

        public static final String SESSION = "session:";
        public static final String AUTH_STATE = "authstate:";
        public static final String ACCOUNT = "account:";
        public static final String RATE_LIMIT = "ratelimit:";
        public static final String APPROVAL = "approval:";
        public static final String CAPTCHA = "captcha:";
        public static final String IP_REPUTATION = "ipreputation:";
        public static final String NONCE = "nonce:";

        /** Код привязки сайта: короткий, вводится игроком. */
        public static final String WEB_LINK_CODE = "weblink:code:";

        /** Секрет браузера для опроса состояния привязки. */
        public static final String WEB_LINK_POLL = "weblink:poll:";

        /** Канал доменных событий между процессами. */
        public static final String CHANNEL_EVENTS = "events";

        /** Канал сброса локального кэша. */
        public static final String CHANNEL_INVALIDATE = "cache-invalidate";

        private Keys() {
            throw new AssertionError("Утилитный класс не подлежит созданию");
        }

        /** Собирает ключ из частей. */
        public static @NotNull String of(@NotNull String prefix, @NotNull Object id) {
            return prefix + id;
        }

        /** Ключ ограничения скорости: область и субъект. */
        public static @NotNull String rateLimit(@NotNull String scope, @NotNull String subject) {
            return RATE_LIMIT + scope + ":" + subject;
        }
    }

    /** Список ключей по шаблону — только для обслуживания и диагностики. */
    @NotNull CompletableFuture<List<String>> keys(@NotNull String pattern);

    /** Значение по ключу или {@code null}, не оборачивая в {@link Optional}. */
    default @NotNull CompletableFuture<String> getOrNull(@NotNull String key) {
        return get(key).thenApply(value -> value.orElse(null));
    }

    /** Записывает значение, если оно не {@code null}. */
    default @NotNull CompletableFuture<Void> setIfNotNull(@NotNull String key,
                                                          @Nullable String value,
                                                          @NotNull Duration ttl) {
        return value == null ? CompletableFuture.completedFuture(null) : set(key, value, ttl);
    }
}
