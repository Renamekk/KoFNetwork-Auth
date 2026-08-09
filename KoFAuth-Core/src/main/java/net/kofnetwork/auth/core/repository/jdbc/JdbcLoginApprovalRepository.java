package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.ApprovalStatus;
import net.kofnetwork.auth.api.model.BotPlatform;
import net.kofnetwork.auth.api.model.DevicePlatform;
import net.kofnetwork.auth.api.model.EventSource;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.LoginApproval;
import net.kofnetwork.auth.api.repository.LoginApprovalRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link LoginApprovalRepository} на JDBC. */
public final class JdbcLoginApprovalRepository implements LoginApprovalRepository {

    private static final String COLUMNS = """
            id, public_id, account_id, username, player_uuid, attempt_id,
            request_source, request_platform, user_agent, device_fingerprint, platform,
            recipient_id, chat_id, request_ip, server, country, city, status,
            issued_at, expires_at, decided_at, decided_by, browser_proof_hash, session_id,
            exchange_consumed_at
            """;

    private final SqlExecutor sql;

    public JdbcLoginApprovalRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull LoginApproval map(@NotNull ResultSet rs) throws SQLException {
        IpAddress ip = SqlTypes.readIp(rs, "request_ip");
        return new LoginApproval(
                rs.getLong("id"),
                rs.getString("public_id"),
                rs.getLong("account_id"),
                rs.getString("username"),
                SqlTypes.readUuid(rs, "player_uuid"),
                rs.getString("attempt_id"),
                SqlTypes.readEnum(rs, "request_source", EventSource.class, EventSource.MINECRAFT),
                SqlTypes.readEnum(rs, "request_platform", DevicePlatform.class,
                        DevicePlatform.MINECRAFT),
                rs.getString("user_agent"),
                rs.getString("device_fingerprint"),
                SqlTypes.readEnum(rs, "platform", BotPlatform.class, BotPlatform.TELEGRAM),
                rs.getLong("recipient_id"),
                rs.getLong("chat_id"),
                ip == null ? IpAddress.UNKNOWN : ip,
                rs.getString("server"),
                rs.getString("country"),
                rs.getString("city"),
                SqlTypes.readEnum(rs, "status", ApprovalStatus.class, ApprovalStatus.PENDING),
                SqlTypes.readRequiredInstant(rs, "issued_at"),
                SqlTypes.readRequiredInstant(rs, "expires_at"),
                SqlTypes.readInstant(rs, "decided_at"),
                SqlTypes.readNullableLong(rs, "decided_by"),
                rs.getString("browser_proof_hash"),
                SqlTypes.readNullableLong(rs, "session_id"),
                SqlTypes.readInstant(rs, "exchange_consumed_at"));
    }

    @Override
    public @NotNull CompletableFuture<LoginApproval> insert(@NotNull LoginApproval approval) {
        return sql.insertReturningKey("""
                        INSERT INTO login_approvals (public_id, account_id, username, player_uuid,
                                                     attempt_id, request_source, request_platform,
                                                     user_agent, device_fingerprint, platform, recipient_id, chat_id,
                                                     request_ip, server, country, city, status,
                                                     issued_at, expires_at, browser_proof_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                approval.publicId(),
                approval.accountId(),
                approval.username(),
                approval.playerUuid() == null ? null : SqlTypes.uuidToBytes(approval.playerUuid()),
                approval.attemptId(),
                approval.requestSource().name(),
                approval.requestPlatform().name(),
                approval.userAgent(),
                approval.deviceFingerprint(),
                approval.platform().name(),
                approval.recipientId(),
                approval.chatId(),
                SqlTypes.ipToBytes(approval.requestIp()),
                approval.server(),
                approval.country(),
                approval.city(),
                approval.status().name(),
                SqlTypes.toTimestamp(approval.issuedAt()),
                SqlTypes.toTimestamp(approval.expiresAt()),
                approval.browserProofHash()
        ).thenApply(approval::withId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<LoginApproval>> findByPublicId(@NotNull String publicId) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM login_approvals WHERE public_id = ?",
                JdbcLoginApprovalRepository::map, publicId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<LoginApproval>> findByAttemptId(@NotNull String attemptId) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM login_approvals WHERE attempt_id = ?",
                JdbcLoginApprovalRepository::map, attemptId);
    }

    @Override
    public @NotNull CompletableFuture<Void> storeCompletedSession(@NotNull String publicId, long sessionId) {
        // Новая password-verified попытка может уже обесценить старую вкладку,
        // пока бот завершает создание её сессии. Не возвращаем такую сессию в
        // старую вкладку: статус обязан остаться EXPIRED.
        return sql.update("UPDATE login_approvals SET session_id = ? "
                        + "WHERE public_id = ? AND status = 'APPROVED'",
                sessionId, publicId).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<ExchangeOutcome> consumeWebExchange(@NotNull String attemptId,
                                                                            @NotNull String proofHash,
                                                                            @NotNull Instant at) {
        return sql.transaction(connection -> {
            boolean consumed;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE login_approvals
                       SET exchange_consumed_at = ?
                     WHERE attempt_id = ? AND browser_proof_hash = ?
                       AND status = 'APPROVED' AND session_id IS NOT NULL
                       AND exchange_consumed_at IS NULL
                    """)) {
                update.setObject(1, SqlTypes.toTimestamp(at));
                update.setString(2, attemptId);
                update.setString(3, proofHash);
                consumed = update.executeUpdate() == 1;
            }
            return new ExchangeOutcome(consumed, readByAttempt(connection, attemptId).orElse(null));
        });
    }

    /**
     * Решение записывается одним {@code UPDATE} с полным условием.
     *
     * <p>{@code status = 'PENDING' AND expires_at > ?} внутри запроса — единственный
     * способ сделать «проверить и записать» неделимым. При двух одновременных нажатиях
     * ровно одно изменит строку; второе получит ноль и прочитает уже записанное решение,
     * а не создаст вторую сессию.
     *
     * <p>Чтение после записи идёт тем же соединением в той же транзакции, иначе
     * проигравший увидел бы состояние до победившего {@code UPDATE}.
     */
    @Override
    public @NotNull CompletableFuture<DecisionOutcome> decide(@NotNull String publicId,
                                                               @NotNull ApprovalStatus decision,
                                                               @NotNull Instant at,
                                                               long decidedBy) {
        return sql.transaction(connection -> {
            boolean applied;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE login_approvals
                       SET status = ?, decided_at = ?, decided_by = ?
                     WHERE public_id = ? AND status = 'PENDING' AND expires_at > ?
                    """)) {
                update.setString(1, decision.name());
                update.setObject(2, SqlTypes.toTimestamp(at));
                update.setLong(3, decidedBy);
                update.setString(4, publicId);
                update.setObject(5, SqlTypes.toTimestamp(at));
                applied = update.executeUpdate() > 0;
            }
            return new DecisionOutcome(applied, read(connection, publicId).orElse(null));
        });
    }

    private static Optional<LoginApproval> read(Connection connection, String publicId)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM login_approvals WHERE public_id = ?")) {
            select.setString(1, publicId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static Optional<LoginApproval> readByAttempt(Connection connection, String attemptId)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM login_approvals WHERE attempt_id = ?")) {
            select.setString(1, attemptId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public @NotNull CompletableFuture<Integer> expireOverdue(@NotNull Instant at) {
        return sql.update("""
                        UPDATE login_approvals SET status = 'EXPIRED', decided_at = ?
                         WHERE status = 'PENDING' AND expires_at <= ?
                        """,
                SqlTypes.toTimestamp(at), SqlTypes.toTimestamp(at));
    }

    @Override
    public @NotNull CompletableFuture<Integer> supersedePending(long accountId,
                                                                 @NotNull String exceptPublicId,
                                                                 @NotNull Instant at) {
        // Прежние кнопки того же аккаунта гасятся как истёкшие: новая попытка входа
        // отменяет предыдущую, и нажатие старой кнопки не должно ничего открывать.
        return sql.update("""
                        UPDATE login_approvals SET status = 'EXPIRED', decided_at = ?
                         WHERE account_id = ? AND public_id <> ?
                           AND (status = 'PENDING'
                                OR (request_source = 'WEB' AND status = 'APPROVED'
                                    AND exchange_consumed_at IS NULL))
                        """,
                SqlTypes.toTimestamp(at), accountId, exceptPublicId);
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteDecidedBefore(@NotNull Instant before) {
        return sql.update(
                "DELETE FROM login_approvals WHERE status <> 'PENDING' AND decided_at < ?",
                SqlTypes.toTimestamp(before));
    }
}
