package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.TotpSecret;
import net.kofnetwork.auth.api.repository.TotpRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import net.kofnetwork.auth.core.security.AesCipher;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link TotpRepository} на JDBC.
 *
 * <p>Секрет шифруется AES-256-GCM внутри репозитория: сервисный слой работает с
 * обычной Base32-строкой и о ключе не знает. Без ключа дамп базы не позволяет
 * генерировать коды второго фактора.
 */
public final class JdbcTotpRepository implements TotpRepository {

    private final SqlExecutor sql;
    private final AesCipher cipher;

    public JdbcTotpRepository(@NotNull SqlExecutor sql, @NotNull AesCipher cipher) {
        this.sql = sql;
        this.cipher = cipher;
    }

    private TotpSecret map(@NotNull ResultSet rs) throws SQLException {
        return new TotpSecret(
                rs.getLong("id"),
                rs.getLong("account_id"),
                cipher.decryptToString(rs.getBytes("secret")),
                rs.getString("algorithm"),
                rs.getInt("digits"),
                rs.getInt("period_seconds"),
                rs.getBoolean("enabled"),
                SqlTypes.readInstant(rs, "confirmed_at"),
                SqlTypes.readNullableLong(rs, "last_used_counter"),
                SqlTypes.readRequiredInstant(rs, "created_at"),
                SqlTypes.readRequiredInstant(rs, "updated_at"));
    }

    @Override
    public @NotNull CompletableFuture<TotpSecret> insert(@NotNull TotpSecret secret) {
        // ON DUPLICATE KEY UPDATE: повторный вызов beginSetup до подтверждения
        // должен перезаписать секрет, а не упасть на уникальном ключе — так игрок
        // может начать заново, если потерял QR-код.
        return sql.withConnectionAsync(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO totp (account_id, secret, algorithm, digits, period_seconds,
                                      enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE secret = VALUES(secret),
                                            algorithm = VALUES(algorithm),
                                            digits = VALUES(digits),
                                            period_seconds = VALUES(period_seconds),
                                            enabled = VALUES(enabled),
                                            confirmed_at = NULL,
                                            last_used_counter = NULL
                    """)) {
                statement.setLong(1, secret.accountId());
                statement.setBytes(2, cipher.encrypt(secret.secret()));
                statement.setString(3, secret.algorithm());
                statement.setInt(4, secret.digits());
                statement.setInt(5, secret.periodSeconds());
                statement.setBoolean(6, secret.enabled());
                statement.setTimestamp(7, SqlTypes.toTimestamp(secret.createdAt()));
                statement.setTimestamp(8, SqlTypes.toTimestamp(secret.updatedAt()));
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM totp WHERE account_id = ?")) {
                statement.setLong(1, secret.accountId());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? secret.withId(rs.getLong("id")) : secret;
                }
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<Optional<TotpSecret>> findByAccount(long accountId) {
        return sql.queryOne("""
                        SELECT id, account_id, secret, algorithm, digits, period_seconds, enabled,
                               confirmed_at, last_used_counter, created_at, updated_at
                        FROM totp WHERE account_id = ?
                        """,
                this::map, accountId);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> enable(long accountId,
                                                      @NotNull Instant at,
                                                      long counter) {
        return sql.update("""
                        UPDATE totp SET enabled = 1, confirmed_at = ?, last_used_counter = ?
                        WHERE account_id = ? AND enabled = 0
                        """,
                SqlTypes.toTimestamp(at), counter, accountId
        ).thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> deleteByAccount(long accountId) {
        return sql.update("DELETE FROM totp WHERE account_id = ?", accountId)
                .thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> compareAndSetCounter(long accountId, long counter) {
        // Условие в WHERE делает операцию защитой от повторного использования кода:
        // два параллельных запроса с одним шестизначным кодом пройдут только раз.
        return sql.update("""
                        UPDATE totp SET last_used_counter = ?
                        WHERE account_id = ? AND (last_used_counter IS NULL OR last_used_counter < ?)
                        """,
                counter, accountId, counter
        ).thenApply(affected -> affected > 0);
    }
}
