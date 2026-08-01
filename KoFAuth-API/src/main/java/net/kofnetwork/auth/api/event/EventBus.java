package net.kofnetwork.auth.api.event;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Шина доменных событий.
 *
 * <p>Реализация в Core совмещает локальную доставку внутри JVM и распространение через
 * Redis Pub/Sub для событий с {@link AuthEvent#isDistributed()}.
 *
 * <p><b>Изоляция подписчиков.</b> Исключение в одном обработчике не должно ни отменять
 * доставку остальным, ни всплывать в код, опубликовавший событие. Упавшее уведомление
 * в Telegram не может помешать игроку войти — реализация логирует сбой и продолжает
 * рассылку.
 */
public interface EventBus {

    /**
     * Публикует событие асинхронно.
     *
     * <p>Возвращаемый future завершается после того, как событие роздано локальным
     * подписчикам и (для распределённых событий) отправлено в Redis. Ждать его
     * в горячем пути не нужно и не следует.
     */
    @NotNull CompletableFuture<Void> publish(@NotNull AuthEvent event);

    /**
     * Публикует событие и завершает future только после отработки всех локальных
     * подписчиков.
     *
     * <p>Нужно там, где следующий шаг зависит от реакции: например, тест должен
     * дождаться записи аудита, прежде чем её проверять. В обычном коде предпочтителен
     * {@link #publish(AuthEvent)}.
     */
    @NotNull CompletableFuture<Void> publishAndAwait(@NotNull AuthEvent event);

    /**
     * Подписывает обработчик на события указанного типа.
     *
     * @return дескриптор для отписки; хранить его обязательно, иначе при
     *         {@code /auth reload} останутся висеть подписки от старых экземпляров
     *         сервисов, и обработчики начнут дублироваться
     */
    <T extends AuthEvent> @NotNull Subscription subscribe(@NotNull Class<T> eventType,
                                                          @NotNull Consumer<T> handler);

    /**
     * Подписывает обработчик с указанием приоритета.
     *
     * @param priority меньшее значение — раньше. Нужно там, где порядок значим:
     *                 инвалидация кэша обязана отработать до того, как обработчик
     *                 уведомлений начнёт перечитывать аккаунт
     */
    <T extends AuthEvent> @NotNull Subscription subscribe(@NotNull Class<T> eventType,
                                                          int priority,
                                                          @NotNull Consumer<T> handler);

    /** Снимает все подписки. Вызывается при остановке модуля. */
    void unsubscribeAll();

    /** Дескриптор подписки. */
    interface Subscription extends AutoCloseable {

        /** Отписывает обработчик. Повторный вызов безопасен. */
        @Override
        void close();

        /** Активна ли подписка. */
        boolean isActive();
    }
}
