package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.LoginHistoryDto;
import net.kofnetwork.auth.api.dto.SecurityLogDto;
import net.kofnetwork.auth.api.model.LoginAttempt;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.Severity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Журналирование действий и чтение истории.
 *
 * <p>Все методы записи возвращают future, но <b>вызывающий код не обязан его ждать</b>.
 * Сбой записи в журнал не должен отменять успешное действие: игрок, у которого не
 * записалась строка аудита, всё равно ввёл верный пароль. Реализация логирует такие
 * сбои и продолжает.
 */
public interface AuditService {

    /** Записывает событие аудита. */
    @NotNull CompletableFuture<Void> log(@Nullable Long accountId,
                                         @NotNull SecurityEventType type,
                                         @NotNull AuthContext context,
                                         @Nullable String message);

    /** Записывает событие с дополнительными данными. */
    @NotNull CompletableFuture<Void> log(@Nullable Long accountId,
                                         @NotNull SecurityEventType type,
                                         @NotNull AuthContext context,
                                         @Nullable String message,
                                         @NotNull Map<String, Object> metadata);

    /**
     * Записывает действие администратора над чужим аккаунтом.
     *
     * @param adminAccountId попадёт в {@code actor_id}: по журналу должно быть видно
     *                       не только что произошло с аккаунтом, но и кто это сделал
     */
    @NotNull CompletableFuture<Void> logAdminAction(long targetAccountId,
                                                    long adminAccountId,
                                                    @NotNull SecurityEventType type,
                                                    @NotNull AuthContext context,
                                                    @Nullable String message);

    /** Записывает попытку входа. */
    @NotNull CompletableFuture<Void> logLoginAttempt(@NotNull LoginAttempt attempt);

    // ------------------------------------------------------------------ чтение

    /** История входов аккаунта. */
    @NotNull CompletableFuture<List<LoginHistoryDto>> getLoginHistory(long accountId, int limit, int offset);

    /** Журнал безопасности аккаунта. */
    @NotNull CompletableFuture<List<SecurityLogDto>> getSecurityLog(long accountId, int limit, int offset);

    /** Журнал аккаунта с фильтром по важности. */
    @NotNull CompletableFuture<List<SecurityLogDto>> getSecurityLog(long accountId,
                                                                    @NotNull Severity minSeverity,
                                                                    int limit,
                                                                    int offset);

    /** Лента событий по всей сети — для панели администратора. */
    @NotNull CompletableFuture<List<SecurityLogDto>> getNetworkLog(@NotNull Severity minSeverity, int limit);

    /**
     * Немедленно сбрасывает буфер записей в базу.
     *
     * <p>Реализация буферизует аудит и пишет пачками. Вызывается при остановке модуля,
     * чтобы события последних секунд не потерялись.
     */
    @NotNull CompletableFuture<Void> flush();
}
