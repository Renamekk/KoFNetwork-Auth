package net.kofnetwork.auth.core.repository.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kofnetwork.auth.api.model.EventSource;
import net.kofnetwork.auth.api.model.SecurityLogEntry;
import net.kofnetwork.auth.api.model.Severity;
import net.kofnetwork.auth.api.repository.SecurityLogRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link SecurityLogRepository} на JDBC. */
public final class JdbcSecurityLogRepository implements SecurityLogRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSecurityLogRepository.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String COLUMNS = """
            id, account_id, actor_id, event_type, severity, source, ip, country, city,
            user_agent, message, metadata, created_at
            """;

    private static final String INSERT_SQL = """
            INSERT INTO security_logs (account_id, actor_id, event_type, severity, source,
                                       ip, country, city, user_agent, message, metadata, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final SqlExecutor sql;

    public JdbcSecurityLogRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull SecurityLogEntry map(@NotNull ResultSet rs) throws SQLException {
        return new SecurityLogEntry(
                rs.getLong("id"),
                SqlTypes.readNullableLong(rs, "account_id"),
                SqlTypes.readNullableLong(rs, "actor_id"),
                rs.getString("event_type"),
                SqlTypes.readEnum(rs, "severity", Severity.class, Severity.INFO),
                SqlTypes.readEnum(rs, "source", EventSource.class, EventSource.SYSTEM),
                SqlTypes.readIp(rs, "ip"),
                rs.getString("country"),
                rs.getString("city"),
                rs.getString("user_agent"),
                rs.getString("message"),
                readMetadata(rs.getString("metadata")),
                SqlTypes.readRequiredInstant(rs, "created_at"));
    }

    private static Map<String, Object> readMetadata(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            LOGGER.warn("Не удалось разобрать metadata записи аудита: {}", e.getMessage());
            return Map.of();
        }
    }

    private static @Nullable String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(metadata);
        } catch (Exception e) {
            LOGGER.warn("Не удалось сериализовать metadata записи аудита: {}", e.getMessage());
            return null;
        }
    }

    private static Object[] toParams(SecurityLogEntry entry) {
        return new Object[]{
                entry.accountId(),
                entry.actorId(),
                entry.eventType(),
                entry.severity().name(),
                entry.source().name(),
                SqlTypes.ipToBytes(entry.ip()),
                entry.country(),
                entry.city(),
                entry.userAgent(),
                entry.message(),
                writeMetadata(entry.metadata()),
                SqlTypes.toTimestamp(entry.createdAt())
        };
    }

    @Override
    public @NotNull CompletableFuture<SecurityLogEntry> insert(@NotNull SecurityLogEntry entry) {
        return sql.insertReturningKey(INSERT_SQL, toParams(entry))
                .thenApply(id -> new SecurityLogEntry(id, entry.accountId(), entry.actorId(),
                        entry.eventType(), entry.severity(), entry.source(), entry.ip(),
                        entry.country(), entry.city(), entry.userAgent(), entry.message(),
                        entry.metadata(), entry.createdAt()));
    }

    @Override
    public @NotNull CompletableFuture<Void> insertBatch(@NotNull List<SecurityLogEntry> entries) {
        if (entries.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        // Одно действие игрока порождает несколько записей аудита. Пакетная вставка
        // превращает их в один сетевой обход — при включённом rewriteBatchedStatements
        // ещё и в один запрос к серверу.
        List<Object[]> batch = new ArrayList<>(entries.size());
        for (SecurityLogEntry entry : entries) {
            batch.add(toParams(entry));
        }
        return sql.batchAtomic(INSERT_SQL, batch)
                .<Void>thenApply(ignored -> null)
                .exceptionallyCompose(failure -> salvage(entries, failure));
    }

    /**
     * Спасает пакет, отвергнутый из-за одной строки.
     *
     * <p><b>Зачем.</b> Пакет — это один запрос к серверу, и отвергается он целиком.
     * Одна негодная строка — например, с {@code actor_id}, которому не нашлось
     * аккаунта, — уносила с собой до полусотни исправных записей о совершенно
     * других событиях: входах, сменах пароля, отзывах сессий. Журнал безопасности,
     * теряющий соседние записи из-за одной испорченной, перестаёт быть журналом.
     *
     * <p>Поэтому отказ по ограничению целостности разбирается построчно: каждая
     * запись отправляется отдельно, негодная называется в логе поимённо и
     * пропускается, все остальные доходят до базы. Пакет к этому моменту откатан
     * целиком ({@link SqlExecutor#batchAtomic}), поэтому повтор ничего не дублирует.
     *
     * <p>Недоступность базы разбирать построчно бессмысленно и вредно: полсотни
     * запросов подряд, каждый со своим ожиданием соединения, — это минуты в потоке
     * сброса буфера. Такой отказ пробрасывается вызывающему как есть.
     */
    private CompletableFuture<Void> salvage(List<SecurityLogEntry> entries, Throwable batchFailure) {
        if (!isConstraintViolation(batchFailure)) {
            return CompletableFuture.failedFuture(batchFailure);
        }
        LOGGER.warn("Пакет аудита из {} записей отвергнут базой, повторяю построчно: {}",
                entries.size(), rootMessage(batchFailure));

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (SecurityLogEntry entry : entries) {
            chain = chain.thenCompose(ignored -> insertOrSkip(entry));
        }
        return chain;
    }

    /** Отдельная запись: негодная пропускается, отказ хранилища прекращает разбор. */
    private CompletableFuture<Void> insertOrSkip(SecurityLogEntry entry) {
        return sql.update(INSERT_SQL, toParams(entry))
                .<Void>thenApply(ignored -> null)
                .exceptionallyCompose(failure -> {
                    if (!isConstraintViolation(failure)) {
                        return CompletableFuture.failedFuture(failure);
                    }
                    LOGGER.error("Запись аудита {} (аккаунт {}, исполнитель {}) отвергнута "
                                    + "базой и потеряна: {}",
                            entry.eventType(), entry.accountId(), entry.actorId(),
                            rootMessage(failure));
                    return CompletableFuture.completedFuture(null);
                });
    }

    /** Нарушение целостности относится к строке, а не к доступности хранилища. */
    private static boolean isConstraintViolation(@Nullable Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SqlExecutor.DuplicateKeyException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    @Override
    public @NotNull CompletableFuture<List<SecurityLogEntry>> findByAccount(long accountId,
                                                                            int limit,
                                                                            int offset) {
        return sql.queryList("""
                        SELECT %s FROM security_logs WHERE account_id = ?
                        ORDER BY created_at DESC LIMIT ? OFFSET ?
                        """.formatted(COLUMNS),
                JdbcSecurityLogRepository::map,
                accountId, clampLimit(limit), Math.max(0, offset));
    }

    @Override
    public @NotNull CompletableFuture<List<SecurityLogEntry>> findByAccountAndSeverity(
            long accountId, @NotNull Severity minSeverity, int limit, int offset) {
        return sql.queryList("""
                        SELECT %s FROM security_logs
                        WHERE account_id = ? AND severity IN (%s)
                        ORDER BY created_at DESC LIMIT ? OFFSET ?
                        """.formatted(COLUMNS, placeholders(atLeast(minSeverity).size())),
                JdbcSecurityLogRepository::map,
                concat(accountId, atLeast(minSeverity), clampLimit(limit), Math.max(0, offset)));
    }

    @Override
    public @NotNull CompletableFuture<List<SecurityLogEntry>> findByEventType(@NotNull String eventType,
                                                                              @NotNull Instant since,
                                                                              int limit) {
        return sql.queryList("""
                        SELECT %s FROM security_logs WHERE event_type = ? AND created_at >= ?
                        ORDER BY created_at DESC LIMIT ?
                        """.formatted(COLUMNS),
                JdbcSecurityLogRepository::map,
                eventType, SqlTypes.toTimestamp(since), clampLimit(limit));
    }

    @Override
    public @NotNull CompletableFuture<List<SecurityLogEntry>> findRecent(@NotNull Severity minSeverity,
                                                                         @Nullable Instant since,
                                                                         int limit) {
        List<String> severities = atLeast(minSeverity);
        Instant from = since == null ? Instant.EPOCH : since;
        return sql.queryList("""
                        SELECT %s FROM security_logs
                        WHERE severity IN (%s) AND created_at >= ?
                        ORDER BY created_at DESC LIMIT ?
                        """.formatted(COLUMNS, placeholders(severities.size())),
                JdbcSecurityLogRepository::map,
                concat(null, severities, SqlTypes.toTimestamp(from), clampLimit(limit)));
    }

    @Override
    public @NotNull CompletableFuture<Integer> countByAccountAndType(long accountId,
                                                                     @NotNull String eventType,
                                                                     @NotNull Instant since) {
        return sql.queryInt("""
                        SELECT COUNT(*) FROM security_logs
                        WHERE account_id = ? AND event_type = ? AND created_at >= ?
                        """,
                0, accountId, eventType, SqlTypes.toTimestamp(since));
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteInfoBefore(@NotNull Instant before) {
        // Только INFO. WARNING и CRITICAL не ротируются никогда: удаление следов
        // инцидента лишает журнал смысла.
        return sql.update("DELETE FROM security_logs WHERE severity = 'INFO' AND created_at < ?",
                SqlTypes.toTimestamp(before));
    }

    /** Уровни важности не ниже указанного. */
    private static List<String> atLeast(Severity minSeverity) {
        List<String> result = new ArrayList<>(3);
        for (Severity severity : Severity.values()) {
            if (severity.ordinal() >= minSeverity.ordinal()) {
                result.add(severity.name());
            }
        }
        return result;
    }

    /**
     * Генерирует {@code ?, ?, ?} для {@code IN}.
     *
     * <p>Единственное место, где часть SQL собирается строкой, и собирается она из
     * количества параметров, а не из их значений: сами значения по-прежнему уходят
     * через {@code PreparedStatement}.
     */
    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] concat(@Nullable Long accountId,
                                   List<String> severities,
                                   Object... tail) {
        List<Object> params = new ArrayList<>();
        if (accountId != null) {
            params.add(accountId);
        }
        params.addAll(severities);
        params.addAll(List.of(tail));
        return params.toArray();
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }
}
