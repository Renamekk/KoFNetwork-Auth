package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.CaptchaStatus;
import net.kofnetwork.auth.api.model.CaptchaType;
import net.kofnetwork.auth.api.repository.CaptchaRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link CaptchaRepository} на JDBC. */
public final class JdbcCaptchaRepository implements CaptchaRepository {

    private static final String COLUMNS = """
            id, account_id, player_uuid, challenge_id, type, expected_answer_hash, attempts,
            max_attempts, ip, status, issued_at, expires_at, resolved_at
            """;

    private final SqlExecutor sql;

    public JdbcCaptchaRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull CaptchaChallenge map(@NotNull ResultSet rs) throws SQLException {
        return new CaptchaChallenge(
                rs.getLong("id"),
                SqlTypes.readNullableLong(rs, "account_id"),
                SqlTypes.readUuid(rs, "player_uuid"),
                rs.getString("challenge_id"),
                SqlTypes.readEnum(rs, "type", CaptchaType.class, CaptchaType.GUI_GRID),
                rs.getString("expected_answer_hash"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                SqlTypes.readRequiredIp(rs, "ip"),
                SqlTypes.readEnum(rs, "status", CaptchaStatus.class, CaptchaStatus.PENDING),
                SqlTypes.readRequiredInstant(rs, "issued_at"),
                SqlTypes.readRequiredInstant(rs, "expires_at"),
                SqlTypes.readInstant(rs, "resolved_at"));
    }

    @Override
    public @NotNull CompletableFuture<CaptchaChallenge> insert(@NotNull CaptchaChallenge challenge) {
        return sql.insertReturningKey("""
                        INSERT INTO captcha (account_id, player_uuid, challenge_id, type,
                                             expected_answer_hash, attempts, max_attempts, ip,
                                             status, issued_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                challenge.accountId(),
                challenge.playerUuid() == null ? null : SqlTypes.uuidToBytes(challenge.playerUuid()),
                challenge.challengeId(),
                challenge.type().name(),
                challenge.expectedAnswerHash(),
                challenge.attempts(),
                challenge.maxAttempts(),
                SqlTypes.ipToBytes(challenge.ip()),
                challenge.status().name(),
                SqlTypes.toTimestamp(challenge.issuedAt()),
                SqlTypes.toTimestamp(challenge.expiresAt())
        ).thenApply(challenge::withId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<CaptchaChallenge>> findByChallengeId(
            @NotNull String challengeId) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM captcha WHERE challenge_id = ?",
                JdbcCaptchaRepository::map, challengeId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<CaptchaChallenge>> findPendingByPlayer(
            @NotNull UUID playerUuid, @NotNull Instant at) {
        return sql.queryOne("""
                        SELECT %s FROM captcha
                        WHERE player_uuid = ? AND status = 'PENDING' AND expires_at > ?
                        ORDER BY issued_at DESC LIMIT 1
                        """.formatted(COLUMNS),
                JdbcCaptchaRepository::map,
                SqlTypes.uuidToBytes(playerUuid), SqlTypes.toTimestamp(at));
    }

    @Override
    public @NotNull CompletableFuture<CaptchaChallenge> update(@NotNull CaptchaChallenge challenge) {
        return sql.update("""
                        UPDATE captcha SET attempts = ?, status = ?, resolved_at = ?
                        WHERE id = ?
                        """,
                challenge.attempts(), challenge.status().name(),
                SqlTypes.toTimestamp(challenge.resolvedAt()), challenge.id()
        ).thenApply(ignored -> challenge);
    }

    @Override
    public @NotNull CompletableFuture<Integer> expireOverdue(@NotNull Instant at) {
        return sql.update("""
                        UPDATE captcha SET status = 'EXPIRED', resolved_at = ?
                        WHERE status = 'PENDING' AND expires_at <= ?
                        """,
                SqlTypes.toTimestamp(at), SqlTypes.toTimestamp(at));
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteResolvedBefore(@NotNull Instant before) {
        return sql.update("DELETE FROM captcha WHERE status <> 'PENDING' AND resolved_at < ?",
                SqlTypes.toTimestamp(before));
    }
}
