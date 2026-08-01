package net.kofnetwork.auth.core.concurrent;

import net.kofnetwork.auth.api.exception.KoFAuthException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Пулы потоков KoFAuth.
 *
 * <p><b>Почему пулы разделены.</b> Если пустить JDBC, Redis и SMTP через один пул,
 * зависший почтовый сервер съест все потоки, и игроки перестанут входить. SMTP по
 * природе медленный (секунды на письмо), JDBC — быстрый (миллисекунды), и смешивать
 * их в одной очереди значит ставить быстрые задачи за медленными.
 *
 * <table>
 *   <caption>Назначение пулов</caption>
 *   <tr><th>Пул</th><th>Нагрузка</th><th>Размер</th></tr>
 *   <tr><td>{@code kofauth-db}</td><td>JDBC</td><td>по размеру пула Hikari</td></tr>
 *   <tr><td>{@code kofauth-io}</td><td>Redis, HTTP, геолокация</td><td>2 × CPU</td></tr>
 *   <tr><td>{@code kofauth-mail}</td><td>SMTP</td><td>2</td></tr>
 *   <tr><td>{@code kofauth-sched}</td><td>периодические задачи</td><td>2</td></tr>
 * </table>
 *
 * <p><b>Почему очереди ограничены.</b> Неограниченная очередь при недоступной базе
 * копит задачи до исчерпания памяти, и вместо деградации аутентификации сервер
 * получает {@code OutOfMemoryError}. С ограниченной очередью лишние задачи
 * отвергаются сразу, future завершается исключением, а игрок видит понятное
 * «сервис временно недоступен».
 *
 * <p><b>Почему не {@code CallerRunsPolicy}.</b> Стандартный приём «выполнить в
 * вызывающем потоке» здесь недопустим: вызывающим часто оказывается главный поток
 * Minecraft, и запрос к базе в нём — ровно то, что весь этот класс призван
 * предотвратить.
 *
 * <p><b>Почему не виртуальные потоки для базы.</b> Число одновременных запросов к
 * MySQL всё равно ограничено пулом соединений Hikari. Тысяча виртуальных потоков
 * на десять соединений просто выстроится в очередь внутри Hikari — с тем же итогом,
 * но менее наблюдаемым.
 */
public final class AsyncExecutors implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncExecutors.class);

    /** Во сколько раз очередь длиннее пула. Запас на всплеск, но не бесконечный. */
    private static final int QUEUE_FACTOR = 50;

    private final ThreadPoolExecutor database;
    private final ThreadPoolExecutor io;
    private final ThreadPoolExecutor mail;
    private final ScheduledExecutorService scheduler;

    private volatile boolean closed;

    /**
     * @param databasePoolSize размер пула для JDBC; задавать равным
     *                         {@code hikari.maximumPoolSize} — больше потоков, чем
     *                         соединений, не ускорит ни один запрос
     */
    public AsyncExecutors(int databasePoolSize) {
        int cpus = Runtime.getRuntime().availableProcessors();
        int dbSize = Math.max(2, databasePoolSize);
        int ioSize = Math.max(4, cpus * 2);

        this.database = fixedPool("kofauth-db", dbSize);
        this.io = fixedPool("kofauth-io", ioSize);
        this.mail = fixedPool("kofauth-mail", 2);
        this.scheduler = Executors.newScheduledThreadPool(2, new NamedThreadFactory("kofauth-sched"));

        LOGGER.info("Пулы KoFAuth запущены: db={}, io={}, mail=2, sched=2", dbSize, ioSize);
    }

    private static ThreadPoolExecutor fixedPool(String name, int size) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                size, size,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(size * QUEUE_FACTOR),
                new NamedThreadFactory(name),
                (task, pool) -> {
                    throw new RejectedExecutionException(
                            "Очередь пула " + name + " переполнена (" + pool.getQueue().size()
                                    + " задач). Вероятно, недоступно хранилище.");
                });
        // Простаивающие потоки освобождаются: держать десять потоков ночью,
        // когда на сервере три игрока, незачем.
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Пул для запросов к базе. */
    public @NotNull Executor database() {
        return database;
    }

    /** Пул для сетевого ввода-вывода: Redis, HTTP, геолокация. */
    public @NotNull Executor io() {
        return io;
    }

    /** Пул для отправки почты. */
    public @NotNull Executor mail() {
        return mail;
    }

    /** Планировщик периодических задач. */
    public @NotNull ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /**
     * Выполняет операцию с базой асинхронно.
     *
     * <p>Отказ пула превращается в исключительно завершённый future, а не в
     * исключение в вызывающем потоке: вызывающий уже настроен обрабатывать
     * неуспех через future, и внезапный {@link RejectedExecutionException}
     * посреди главного потока Minecraft ему ничем не поможет.
     */
    public <T> @NotNull CompletableFuture<T> supplyDatabase(@NotNull Supplier<T> task) {
        return supplySafely(task, database);
    }

    /** Выполняет операцию ввода-вывода асинхронно. */
    public <T> @NotNull CompletableFuture<T> supplyIo(@NotNull Supplier<T> task) {
        return supplySafely(task, io);
    }

    /** Выполняет отправку почты асинхронно. */
    public <T> @NotNull CompletableFuture<T> supplyMail(@NotNull Supplier<T> task) {
        return supplySafely(task, mail);
    }

    private <T> CompletableFuture<T> supplySafely(Supplier<T> task, Executor executor) {
        if (closed) {
            return CompletableFuture.failedFuture(
                    new KoFAuthException("KoFAuth остановлен, новые задачи не принимаются"));
        }
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Останавливает пулы.
     *
     * <p>Порядок важен: сначала перестаём принимать новое, потом даём доработать
     * принятому. Планировщик глушится первым — иначе он успеет поставить в очередь
     * задачу к уже закрывающемуся пулу базы.
     *
     * @param timeout сколько ждать завершения принятых задач
     */
    public void shutdown(@NotNull Duration timeout) {
        if (closed) {
            return;
        }
        closed = true;

        scheduler.shutdown();
        database.shutdown();
        io.shutdown();
        mail.shutdown();

        long deadline = System.nanoTime() + timeout.toNanos();
        awaitTermination(scheduler, deadline, "sched");
        awaitTermination(database, deadline, "db");
        awaitTermination(io, deadline, "io");
        awaitTermination(mail, deadline, "mail");

        LOGGER.info("Пулы KoFAuth остановлены");
    }

    private static void awaitTermination(ExecutorService executor, long deadlineNanos, String name) {
        long remaining = deadlineNanos - System.nanoTime();
        try {
            if (remaining <= 0 || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                int dropped = executor.shutdownNow().size();
                if (dropped > 0) {
                    LOGGER.warn("Пул {} не завершился в срок, отброшено задач: {}", name, dropped);
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        shutdown(Duration.ofSeconds(15));
    }

    /** Принимают ли пулы новые задачи. */
    public boolean isRunning() {
        return !closed;
    }

    /** Снимок загрузки пулов — для {@code /auth info} и метрик. */
    public @NotNull PoolStats stats() {
        return new PoolStats(
                database.getActiveCount(), database.getQueue().size(),
                io.getActiveCount(), io.getQueue().size(),
                mail.getActiveCount(), mail.getQueue().size());
    }

    /** Загрузка пулов. */
    public record PoolStats(int databaseActive, int databaseQueued,
                            int ioActive, int ioQueued,
                            int mailActive, int mailQueued) {
    }
}
