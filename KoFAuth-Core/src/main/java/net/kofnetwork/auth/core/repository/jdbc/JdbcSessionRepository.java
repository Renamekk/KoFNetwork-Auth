package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.api.repository.SessionRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link SessionRepository} на JDBC. */
public final class JdbcSessionRepository implements SessionRepository {

    private static final String COLUMNS = """
            id, account_id, device_id, public_id, type, ip, user_agent, country, city,
            server, issued_at, last_seen_at, expires_at, absolute_expires_at,
            revoked, revoked_at, revoked_reason
            """;

    private final SqlExecutor sql;

    public JdbcSessionRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull Session map(@NotNull ResultSet rs) throws SQLException {
        return new Session(
                rs.getLong("id"),
                rs.getLong("account_id"),
                SqlTypes.readNullableLong(rs, "device_id"),
                rs.getString("public_id"),
                SqlTypes.readEnum(rs, "type", SessionType.class, SessionType.GAME),
                SqlTypes.readRequiredIp(rs, "ip"),
                rs.getString("user_agent"),
                rs.getString("country"),
                rs.getString("city"),
                rs.getString("server"),
                SqlTypes.readRequiredInstant(rs, "issued_at"),
                SqlTypes.readRequiredInstant(rs, "last_seen_at"),
                SqlTypes.readRequiredInstant(rs, "expires_at"),
                SqlTypes.readRequiredInstant(rs, "absolute_expires_at"),
                rs.getBoolean("revoked"),
                SqlTypes.readInstant(rs, "revoked_at"),
                rs.getString("revoked_reason"));
    }

    @Override
    public @NotNull CompletableFuture<Session> insert(@NotNull Session session) {
        return sql.insertReturningKey("""
                        INSERT INTO sessions (account_id, device_id, public_id, type, ip, user_agent,
                                              country, city, server, issued_at, last_seen_at,
                                              expires_at, absolute_expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                session.accountId(),
                session.deviceId(),
                session.publicId(),
                session.type().name(),
                SqlTypes.ipToBytes(session.ip()),
                session.userAgent(),
                session.country(),
                session.city(),
                session.server(),
                SqlTypes.toTimestamp(session.issuedAt()),
                SqlTypes.toTimestamp(session.lastSeenAt()),
                SqlTypes.toTimestamp(session.expiresAt()),
                SqlTypes.toTimestamp(session.absoluteExpiresAt())
        ).thenApply(session::withId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<Session>> findByPublicId(@NotNull String publicId) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM sessions WHERE public_id = ?",
                JdbcSessionRepository::map, publicId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<Session>> findById(long id) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM sessions WHERE id = ?",
                JdbcSessionRepository::map, id);
    }

    @Override
    public @NotNull CompletableFuture<List<Session>> findActiveByAccount(long accountId,
                                                                         @NotNull Instant at) {
        // Порядок условий соответствует индексу idx_sessions_account_active
        // (account_id, revoked, expires_at).
        return sql.queryList("""
                        SELECT %s FROM sessions
                        WHERE account_id = ? AND revoked = 0
                          AND expires_at > ? AND absolute_expires_at > ?
                        ORDER BY last_seen_at DESC
                        """.formatted(COLUMNS),
                JdbcSessionRepository::map,
                accountId, SqlTypes.toTimestamp(at), SqlTypes.toTimestamp(at));
    }

    @Override
    public @NotNull CompletableFuture<List<Session>> findByAccount(long accountId, int limit, int offset) {
        return sql.queryList("""
                        SELECT %s FROM sessions WHERE account_id = ?
                        ORDER BY issued_at DESC LIMIT ? OFFSET ?
                        """.formatted(COLUMNS),
                JdbcSessionRepository::map,
                accountId, clampLimit(limit), Math.max(0, offset));
    }

    @Override
    public @NotNull CompletableFuture<List<Session>> findActiveByAccountAndType(long accountId,
                                                                                @NotNull SessionType type,
                                                                                @NotNull Instant at) {
        return sql.queryList("""
                        SELECT %s FROM sessions
                        WHERE account_id = ? AND type = ? AND revoked = 0
                          AND expires_at > ? AND absolute_expires_at > ?
                        ORDER BY last_seen_at DESC
                        """.formatted(COLUMNS),
                JdbcSessionRepository::map,
                accountId, type.name(), SqlTypes.toTimestamp(at), SqlTypes.toTimestamp(at));
    }

    @Override
    public @NotNull CompletableFuture<Void> touch(long sessionId,
                                                  @NotNull Instant lastSeenAt,
                                                  @NotNull Instant expiresAt) {
        // LEAST не даёт скользящему сроку перепрыгнуть жёсткий потолок: иначе
        // угнанную сессию можно было бы «прогревать» бесконечно.
        return sql.update("""
                        UPDATE sessions
                        SET last_seen_at = ?, expires_at = LEAST(?, absolute_expires_at)
                        WHERE id = ? AND revoked = 0
                        """,
                SqlTypes.toTimestamp(lastSeenAt), SqlTypes.toTimestamp(expiresAt), sessionId
        ).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> updateServer(long sessionId, @NotNull String server) {
        return sql.update("UPDATE sessions SET server = ? WHERE id = ?", server, sessionId)
                .thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> revoke(@NotNull String publicId,
                                                      @NotNull Instant at,
                                                      @NotNull String reason) {
        // Условие revoked = 0 делает операцию идемпотентной и сохраняет исходную
        // причину отзыва при повторном вызове.
        return sql.update("""
                        UPDATE sessions SET revoked = 1, revoked_at = ?, revoked_reason = ?
                        WHERE public_id = ? AND revoked = 0
                        """,
                SqlTypes.toTimestamp(at), reason, publicId
        ).thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<Integer> revokeAllForAccount(long accountId,
                                                                   @Nullable String exceptPublicId,
                                                                   @NotNull Instant at,
                                                                   @NotNull String reason) {
        if (exceptPublicId == null) {
            return sql.update("""
                            UPDATE sessions SET revoked = 1, revoked_at = ?, revoked_reason = ?
                            WHERE account_id = ? AND revoked = 0
                            """,
                    SqlTypes.toTimestamp(at), reason, accountId);
        }
        return sql.update("""
                        UPDATE sessions SET revoked = 1, revoked_at = ?, revoked_reason = ?
                        WHERE account_id = ? AND revoked = 0 AND public_id <> ?
                        """,
                SqlTypes.toTimestamp(at), reason, accountId, exceptPublicId);
    }

    @Override
    public @NotNull CompletableFuture<Integer> revokeExpired(@NotNull Instant at) {
        return sql.update("""
                        UPDATE sessions SET revoked = 1, revoked_at = ?, revoked_reason = ?
                        WHERE revoked = 0 AND (expires_at <= ? OR absolute_expires_at <= ?)
                        """,
                SqlTypes.toTimestamp(at), Session.REASON_TIMEOUT,
                SqlTypes.toTimestamp(at), SqlTypes.toTimestamp(at));
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteRevokedBefore(@NotNull Instant before) {
        return sql.update("DELETE FROM sessions WHERE revoked = 1 AND revoked_at < ?",
                SqlTypes.toTimestamp(before));
    }

    @Override
    public @NotNull CompletableFuture<Integer> countActive(long accountId, @NotNull Instant at) {
        return sql.queryInt("""
                        SELECT COUNT(*) FROM sessions
                        WHERE account_id = ? AND revoked = 0 AND expires_at > ?
                        """,
                0, accountId, SqlTypes.toTimestamp(at));
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }
}
