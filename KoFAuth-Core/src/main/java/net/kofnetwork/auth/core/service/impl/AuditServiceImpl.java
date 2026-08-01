package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.LoginHistoryDto;
import net.kofnetwork.auth.api.dto.SecurityLogDto;
import net.kofnetwork.auth.api.model.LoginAttempt;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.SecurityLogEntry;
import net.kofnetwork.auth.api.model.Severity;
import net.kofnetwork.auth.api.repository.LoginHistoryRepository;
import net.kofnetwork.auth.api.repository.SecurityLogRepository;
import net.kofnetwork.auth.api.service.AuditService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Реализация {@link AuditService} с буферизацией записи.
 *
 * <p><b>Зачем буфер.</b> Одно действие игрока порождает три-четыре записи аудита
 * (вход, создание сессии, новое устройство). При 40 логинах в секунду это 160
 * отдельных {@code INSERT}, каждый со своим сетевым обходом. Буфер собирает их
 * в пачки и отдаёт одним запросом.
 *
 * <p><b>Сбой записи не отменяет действие.</b> Игрок, у которого не записалась строка
 * аудита, всё равно ввёл верный пароль. Ошибки логируются, но наружу не всплывают —
 * иначе недоступность журнала оборачивалась бы отказом входа.
 */
public final class AuditServiceImpl implements AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditServiceImpl.class);

    /** Сброс буфера при достижении этого размера. */
    private static final int FLUSH_THRESHOLD = 50;

    /** Периодический сброс: события не должны залёживаться в памяти. */
    private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(5);

    /**
     * Потолок буфера. При недоступной базе очередь иначе растёт без предела:
     * события продолжают поступать, а сбросить их некуда.
     */
    private static final int MAX_BUFFERED = 10_000;

    private final SecurityLogRepository securityLogs;
    private final LoginHistoryRepository loginHistory;

    private final ConcurrentLinkedQueue<SecurityLogEntry> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger buffered = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();

    public AuditServiceImpl(@NotNull SecurityLogRepository securityLogs,
                            @NotNull LoginHistoryRepository loginHistory,
                            @NotNull ScheduledExecutorService scheduler) {
        this.securityLogs = securityLogs;
        this.loginHistory = loginHistory;

        scheduler.scheduleWithFixedDelay(
                () -> flush().exceptionally(e -> {
                    LOGGER.error("Не удалось сбросить буфер аудита", e);
                    return null;
                }),
                FLUSH_INTERVAL.toMillis(), FLUSH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public @NotNull CompletableFuture<Void> log(@Nullable Long accountId,
                                                @NotNull SecurityEventType type,
                                                @NotNull AuthContext context,
                                                @Nullable String message) {
        return log(accountId, type, context, message, Map.of());
    }

    @Override
    public @NotNull CompletableFuture<Void> log(@Nullable Long accountId,
                                                @NotNull SecurityEventType type,
                                                @NotNull AuthContext context,
                                                @Nullable String message,
                                                @NotNull Map<String, Object> metadata) {
        SecurityLogEntry entry = SecurityLogEntry
                .of(accountId, type, context.source(), context.ip(), message)
                .withGeo(context.country(), context.city())
                .withUserAgent(context.userAgent())
                .withMetadata(metadata);
        return enqueue(entry);
    }

    @Override
    public @NotNull CompletableFuture<Void> logAdminAction(long targetAccountId,
                                                           long adminAccountId,
                                                           @NotNull SecurityEventType type,
                                                           @NotNull AuthContext context,
                                                           @Nullable String message) {
        SecurityLogEntry entry = SecurityLogEntry
                .byAdmin(targetAccountId, adminAccountId, type, context.source(), message)
                .withGeo(context.country(), context.city());
        return enqueue(entry);
    }

    /**
     * Кладёт запись в буфер.
     *
     * <p>Критические события сбрасываются немедленно: между обнаружением взлома и
     * появлением записи в базе не должно быть пяти секунд, за которые процесс может
     * упасть вместе с буфером.
     */
    private CompletableFuture<Void> enqueue(SecurityLogEntry entry) {
        if (buffered.get() >= MAX_BUFFERED) {
            int total = dropped.incrementAndGet();
            if (total % 1000 == 1) {
                LOGGER.error("Буфер аудита переполнен, отброшено записей: {}. "
                        + "Вероятно, база недоступна.", total);
            }
            return CompletableFuture.completedFuture(null);
        }

        buffer.add(entry);
        int size = buffered.incrementAndGet();

        if (entry.severity() == Severity.CRITICAL || size >= FLUSH_THRESHOLD) {
            return flush();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletableFuture<Void> logLoginAttempt(@NotNull LoginAttempt attempt) {
        // История входов не буферизуется: записей на порядок меньше, чем событий
        // аудита, а её полнота важнее экономии на обходах.
        return loginHistory.insert(attempt)
                .<Void>thenApply(saved -> null)
                .exceptionally(e -> {
                    LOGGER.error("Не удалось записать попытку входа для '{}'",
                            attempt.usernameAttempt(), e);
                    return null;
                });
    }

    @Override
    public @NotNull CompletableFuture<List<LoginHistoryDto>> getLoginHistory(long accountId,
                                                                             int limit,
                                                                             int offset) {
        return loginHistory.findByAccount(accountId, limit, offset)
                .thenApply(attempts -> attempts.stream().map(LoginHistoryDto::from).toList());
    }

    @Override
    public @NotNull CompletableFuture<List<SecurityLogDto>> getSecurityLog(long accountId,
                                                                           int limit,
                                                                           int offset) {
        return securityLogs.findByAccount(accountId, limit, offset)
                .thenApply(entries -> entries.stream().map(SecurityLogDto::from).toList());
    }

    @Override
    public @NotNull CompletableFuture<List<SecurityLogDto>> getSecurityLog(long accountId,
                                                                           @NotNull Severity minSeverity,
                                                                           int limit,
                                                                           int offset) {
        return securityLogs.findByAccountAndSeverity(accountId, minSeverity, limit, offset)
                .thenApply(entries -> entries.stream().map(SecurityLogDto::from).toList());
    }

    @Override
    public @NotNull CompletableFuture<List<SecurityLogDto>> getNetworkLog(@NotNull Severity minSeverity,
                                                                          int limit) {
        return securityLogs.findRecent(minSeverity, null, limit)
                .thenApply(entries -> entries.stream().map(SecurityLogDto::from).toList());
    }

    @Override
    public @NotNull CompletableFuture<Void> flush() {
        List<SecurityLogEntry> batch = new ArrayList<>();
        SecurityLogEntry entry;
        while ((entry = buffer.poll()) != null) {
            batch.add(entry);
            buffered.decrementAndGet();
        }
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return securityLogs.insertBatch(batch)
                .exceptionally(e -> {
                    // Записи уже извлечены из очереди. Возвращать их обратно нельзя:
                    // при устойчивом отказе базы это дало бы бесконечный цикл повторов
                    // и рост памяти. Факт потери фиксируется в логе процесса.
                    LOGGER.error("Потеряно записей аудита при сбросе: {}", batch.size(), e);
                    return null;
                });
    }

    /** Сколько записей ждёт сброса. Для {@code /auth info} и метрик. */
    public int bufferedCount() {
        return buffered.get();
    }

    /** Сколько записей отброшено из-за переполнения буфера. */
    public int droppedCount() {
        return dropped.get();
    }
}
