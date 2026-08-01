package net.kofnetwork.auth.core.event;

import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.event.EventBus;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Шина событий внутри одного процесса.
 *
 * <p><b>Изоляция подписчиков.</b> Исключение в обработчике логируется и не мешает
 * ни остальным обработчикам, ни коду, опубликовавшему событие. Упавшая отправка
 * уведомления в Telegram не может помешать игроку войти — это главное требование
 * к шине, и без него развязка через события давала бы больше вреда, чем пользы.
 *
 * <p><b>Наследование учитывается.</b> Подписка на {@link AuthEvent} получает все
 * события; подписка на конкретный тип — только его. Это позволяет аудиту слушать
 * всё одним обработчиком, а уведомлениям — только интересные им типы.
 *
 * <p>Межпроцессная доставка сюда не входит: ею занимается {@link RedisEventBridge},
 * который надстраивается над этой шиной.
 */
public final class SimpleEventBus implements EventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleEventBus.class);

    /** Приоритет по умолчанию. Меньше — раньше. */
    public static final int DEFAULT_PRIORITY = 100;

    private final Map<Class<? extends AuthEvent>, List<RegisteredHandler<?>>> handlers =
            new ConcurrentHashMap<>();
    private final Executor executor;

    /**
     * @param executor пул, на котором выполняются обработчики. Не главный поток
     *                 Minecraft: обработчик аудита пишет в базу
     */
    public SimpleEventBus(@NotNull Executor executor) {
        this.executor = executor;
    }

    @Override
    public @NotNull CompletableFuture<Void> publish(@NotNull AuthEvent event) {
        return CompletableFuture.runAsync(() -> dispatch(event), executor);
    }

    /**
     * В локальной шине совпадает с {@link #publish(AuthEvent)}: раздача подписчикам
     * синхронна внутри задачи, поэтому возвращаемый future в обоих случаях
     * завершается после отработки всех обработчиков. Разница появляется в
     * {@link RedisEventBridge}, где {@code publish} не дожидается отправки в Redis.
     */
    @Override
    public @NotNull CompletableFuture<Void> publishAndAwait(@NotNull AuthEvent event) {
        return publish(event);
    }

    /**
     * Раздаёт событие подписчикам синхронно, в порядке приоритета.
     *
     * <p>Открыт для {@link RedisEventBridge}: событие, пришедшее с другого узла,
     * должно попасть локальным подписчикам, но не уйти обратно в Redis — иначе
     * получится бесконечный цикл пересылки между узлами.
     */
    void dispatch(@NotNull AuthEvent event) {
        List<RegisteredHandler<?>> matching = collectHandlers(event.getClass());
        if (matching.isEmpty()) {
            return;
        }
        for (RegisteredHandler<?> handler : matching) {
            if (!handler.active.get()) {
                continue;
            }
            try {
                handler.invoke(event);
            } catch (RuntimeException e) {
                LOGGER.error("Ошибка в обработчике события {} (подписка на {})",
                        event.getClass().getSimpleName(),
                        handler.eventType.getSimpleName(), e);
            }
        }
    }

    /**
     * Собирает обработчики, подходящие событию: подписанные на его класс и на любой
     * его надтип, включая {@link AuthEvent}.
     */
    private List<RegisteredHandler<?>> collectHandlers(Class<?> eventClass) {
        List<RegisteredHandler<?>> result = new ArrayList<>();
        for (Map.Entry<Class<? extends AuthEvent>, List<RegisteredHandler<?>>> entry : handlers.entrySet()) {
            if (entry.getKey().isAssignableFrom(eventClass)) {
                result.addAll(entry.getValue());
            }
        }
        result.sort(Comparator.comparingInt(handler -> handler.priority));
        return result;
    }

    @Override
    public <T extends AuthEvent> @NotNull Subscription subscribe(@NotNull Class<T> eventType,
                                                                  @NotNull Consumer<T> handler) {
        return subscribe(eventType, DEFAULT_PRIORITY, handler);
    }

    @Override
    public <T extends AuthEvent> @NotNull Subscription subscribe(@NotNull Class<T> eventType,
                                                                  int priority,
                                                                  @NotNull Consumer<T> handler) {
        RegisteredHandler<T> registered = new RegisteredHandler<>(eventType, priority, handler);
        handlers.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(registered);
        return registered;
    }

    @Override
    public void unsubscribeAll() {
        handlers.values().forEach(list -> list.forEach(handler -> handler.active.set(false)));
        handlers.clear();
    }

    /** Число активных подписок — для диагностики и тестов. */
    public int subscriptionCount() {
        return handlers.values().stream()
                .mapToInt(list -> (int) list.stream().filter(h -> h.active.get()).count())
                .sum();
    }

    /** Зарегистрированный обработчик, он же дескриптор подписки. */
    private final class RegisteredHandler<T extends AuthEvent> implements Subscription {

        private final Class<T> eventType;
        private final int priority;
        private final Consumer<T> handler;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private RegisteredHandler(Class<T> eventType, int priority, Consumer<T> handler) {
            this.eventType = eventType;
            this.priority = priority;
            this.handler = handler;
        }

        @SuppressWarnings("unchecked")
        void invoke(AuthEvent event) {
            handler.accept((T) event);
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                List<RegisteredHandler<?>> list = handlers.get(eventType);
                if (list != null) {
                    list.remove(this);
                }
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }
    }
}
