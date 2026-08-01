package net.kofnetwork.auth.core.concurrent;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фабрика именованных потоков.
 *
 * <p>Имя вида {@code kofauth-db-3} — не косметика. Когда на сервере с двадцатью плагинами
 * начинается лаг, первое, что делает администратор, — снимает дамп потоков. Пул из
 * безымянных {@code pool-7-thread-2} в этот момент бесполезен: непонятно даже, чей он.
 *
 * <p>Все потоки создаются как демоны: зависшая задача в пуле не должна мешать JVM
 * завершиться после остановки сервера.
 */
public final class NamedThreadFactory implements ThreadFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(NamedThreadFactory.class);

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger(1);

    /**
     * @param prefix префикс имени, например {@code kofauth-db}
     */
    public NamedThreadFactory(@NotNull String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(@NotNull Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
        thread.setDaemon(true);
        // Пулы KoFAuth не должны конкурировать с главным потоком Minecraft за процессор:
        // лучше отдать вход на сотню миллисекунд позже, чем просадить тик всему серверу.
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.setUncaughtExceptionHandler((t, e) ->
                LOGGER.error("Необработанное исключение в потоке {}", t.getName(), e));
        return thread;
    }
}
