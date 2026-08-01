package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.SecurityLogEntry;
import net.kofnetwork.auth.api.model.Severity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Доступ к таблице {@code security_logs}. */
public interface SecurityLogRepository {

    @NotNull CompletableFuture<SecurityLogEntry> insert(@NotNull SecurityLogEntry entry);

    /**
     * Пакетная запись.
     *
     * <p>Аудит порождает несколько записей на одно действие игрока (вход, создание
     * сессии, новое устройство). Отправлять их по одной — три сетевых обхода вместо
     * одного; реализация буферизует записи и сбрасывает пачкой.
     */
    @NotNull CompletableFuture<Void> insertBatch(@NotNull List<SecurityLogEntry> entries);

    /** Журнал аккаунта от новых записей к старым. */
    @NotNull CompletableFuture<List<SecurityLogEntry>> findByAccount(long accountId, int limit, int offset);

    /** Журнал аккаунта с фильтром по важности. */
    @NotNull CompletableFuture<List<SecurityLogEntry>> findByAccountAndSeverity(long accountId,
                                                                                @NotNull Severity minSeverity,
                                                                                int limit,
                                                                                int offset);

    /** Записи указанного типа за период по всей сети. */
    @NotNull CompletableFuture<List<SecurityLogEntry>> findByEventType(@NotNull String eventType,
                                                                       @NotNull Instant since,
                                                                       int limit);

    /** Лента событий важности не ниже указанной — для панели администратора. */
    @NotNull CompletableFuture<List<SecurityLogEntry>> findRecent(@NotNull Severity minSeverity,
                                                                  @Nullable Instant since,
                                                                  int limit);

    /** Число событий типа по аккаунту за период. */
    @NotNull CompletableFuture<Integer> countByAccountAndType(long accountId,
                                                              @NotNull String eventType,
                                                              @NotNull Instant since);

    /**
     * Удаляет записи важности {@link Severity#INFO} старше указанного момента.
     *
     * <p>{@link Severity#WARNING} и {@link Severity#CRITICAL} не удаляются никогда:
     * ротация следов инцидента лишает смысла сам журнал.
     */
    @NotNull CompletableFuture<Integer> deleteInfoBefore(@NotNull Instant before);
}
