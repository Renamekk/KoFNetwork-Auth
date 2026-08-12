package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.EventSource;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.LoginAttempt;
import net.kofnetwork.auth.api.model.LoginResultType;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import net.kofnetwork.auth.api.repository.LoginHistoryRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link LoginHistoryRepository} на JDBC. */
public final class JdbcLoginHistoryRepository implements LoginHistoryRepository {

    private static final String COLUMNS = """
            id, account_id, device_id, username_attempt, success, result, ip, country, city,
            isp, user_agent, source, server, protocol_version, two_factor_method, created_at
            """;

    private final SqlExecutor sql;

    public JdbcLoginHistoryRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull LoginAttempt map(@NotNull ResultSet rs) throws SQLException {
        return new LoginAttempt(
                rs.getLong("id"),
                SqlTypes.readNullableLong(rs, "account_id"),
                SqlTypes.readNullableLong(rs, "device_id"),
                rs.getString("username_attempt"),
                rs.getBoolean("success"),
                SqlTypes.readEnum(rs, "result", LoginResultType.class, LoginResultType.ERROR),
                SqlTypes.readRequiredIp(rs, "ip"),
                rs.getString("country"),
                rs.getString("city"),
                rs.getString("isp"),
                rs.getString("user_agent"),
                SqlTypes.readEnum(rs, "source", EventSource.class, EventSource.MINECRAFT),
                rs.getString("server"),
                SqlTypes.readNullableInt(rs, "protocol_version"),
                SqlTypes.readNullableEnum(rs, "two_factor_method", TwoFactorMethod.class),
                SqlTypes.readRequiredInstant(rs, "created_at"));
    }

    @Override
    public @NotNull CompletableFuture<LoginAttempt> insert(@NotNull LoginAttempt attempt) {
        return sql.insertReturningKey("""
                        INSERT INTO login_history (account_id, device_id, username_attempt, success,
                                                   result, ip, country, city, isp, user_agent,
                                                   source, server, protocol_version,
                                                   two_factor_method, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                attempt.accountId(),
                attempt.deviceId(),
                truncateUsername(attempt.usernameAttempt()),
                attempt.success(),
                attempt.result().name(),
                SqlTypes.ipToBytes(attempt.ip()),
                attempt.country(),
                attempt.city(),
                attempt.isp(),
                attempt.userAgent(),
                attempt.source().name(),
                attempt.server(),
                attempt.protocolVersion(),
                attempt.twoFactorMethod() == null ? null : attempt.twoFactorMethod().name(),
                SqlTypes.toTimestamp(attempt.createdAt())
        ).thenApply(id -> new LoginAttempt(id, attempt.accountId(), attempt.deviceId(),
                attempt.usernameAttempt(), attempt.success(), attempt.result(), attempt.ip(),
                attempt.country(), attempt.city(), attempt.isp(), attempt.userAgent(),
                attempt.source(), attempt.server(), attempt.protocolVersion(),
                attempt.twoFactorMethod(), attempt.createdAt()));
    }

    @Override
    public @NotNull CompletableFuture<List<LoginAttempt>> findByAccount(long accountId,
                                                                        int limit,
                                                                        int offset) {
        // ORDER BY created_at DESC обслуживается обратным проходом по
        // idx_login_history_account_time без сортировки.
        return sql.queryList("""
                        SELECT %s FROM login_history WHERE account_id = ?
                        ORDER BY created_at DESC LIMIT ? OFFSET ?
                        """.formatted(COLUMNS),
                JdbcLoginHistoryRepository::map,
                accountId, clampLimit(limit), Math.max(0, offset));
    }

    @Override
    public @NotNull CompletableFuture<List<LoginAttempt>> findByIp(@NotNull IpAddress ip,
                                                                   @NotNull Instant since,
                                                                   int limit) {
        return sql.queryList("""
                        SELECT %s FROM login_history WHERE ip = ? AND created_at >= ?
                        ORDER BY created_at DESC LIMIT ?
                        """.formatted(COLUMNS),
                JdbcLoginHistoryRepository::map,
                SqlTypes.ipToBytes(ip), SqlTypes.toTimestamp(since), clampLimit(limit));
    }

    @Override
    public @NotNull CompletableFuture<Integer> countFailedSince(long accountId, @NotNull Instant since) {
        return sql.queryInt("""
                        SELECT COUNT(*) FROM login_history
                        WHERE account_id = ? AND success = 0 AND created_at >= ?
                        """,
                0, accountId, SqlTypes.toTimestamp(since));
    }

    @Override
    public @NotNull CompletableFuture<Integer> countFailedFromIpSince(@NotNull IpAddress ip,
                                                                      @NotNull Instant since) {
        return sql.queryInt("""
                        SELECT COUNT(*) FROM login_history
                        WHERE ip = ? AND success = 0 AND created_at >= ?
                        """,
                0, SqlTypes.ipToBytes(ip), SqlTypes.toTimestamp(since));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasSuccessfulLoginFromCountry(long accountId,
                                                                             @NotNull String country) {
        return sql.queryInt("""
                        SELECT 1 FROM login_history
                        WHERE account_id = ? AND success = 1 AND country = ? LIMIT 1
                        """,
                0, accountId, country
        ).thenApply(found -> found == 1);
    }

    @Override
    public @NotNull CompletableFuture<Optional<LoginAttempt>> findLastSuccessful(long accountId) {
        return sql.queryOne("""
                        SELECT %s FROM login_history WHERE account_id = ? AND success = 1
                        ORDER BY created_at DESC LIMIT 1
                        """.formatted(COLUMNS),
                JdbcLoginHistoryRepository::map, accountId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<LoginAttempt>> findFirstSuccessful(long accountId) {
        // Прямой проход по тому же idx_login_history_account_time, что и у
        // findLastSuccessful, только с начала: сортировать нечего.
        return sql.queryOne("""
                        SELECT %s FROM login_history WHERE account_id = ? AND success = 1
                        ORDER BY created_at ASC LIMIT 1
                        """.formatted(COLUMNS),
                JdbcLoginHistoryRepository::map, accountId);
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteBefore(@NotNull Instant before) {
        return sql.update("DELETE FROM login_history WHERE created_at < ?",
                SqlTypes.toTimestamp(before));
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }

    /**
     * Обрезает ник до ширины колонки {@code username_attempt VARCHAR(16)}.
     *
     * <p>Ник Minecraft не бывает длиннее 16 символов, поэтому более длинное значение
     * приходит либо от подменённого клиента, либо из веб-API. Отдать его в базу как
     * есть означало бы получить {@code Data truncation} и потерять <em>всю</em> запись
     * о попытке входа — то есть именно ту запись, которая фиксирует аномалию.
     * Обрезка сохраняет след события; сам факт нестандартной длины виден по
     * сопутствующему событию аудита.
     */
    private static String truncateUsername(String username) {
        return username.length() <= 16 ? username : username.substring(0, 16);
    }
}
