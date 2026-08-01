package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.EmailBinding;
import net.kofnetwork.auth.api.repository.EmailRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link EmailRepository} на JDBC. */
public final class JdbcEmailRepository implements EmailRepository {

    private static final String COLUMNS = """
            id, account_id, email, email_lower, verified, verified_at, is_primary,
            notify_login, notify_security, notify_newsletter, created_at, updated_at
            """;

    private final SqlExecutor sql;

    public JdbcEmailRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull EmailBinding map(@NotNull ResultSet rs) throws SQLException {
        return new EmailBinding(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("email"),
                rs.getString("email_lower"),
                rs.getBoolean("verified"),
                SqlTypes.readInstant(rs, "verified_at"),
                rs.getBoolean("is_primary"),
                rs.getBoolean("notify_login"),
                rs.getBoolean("notify_security"),
                rs.getBoolean("notify_newsletter"),
                SqlTypes.readRequiredInstant(rs, "created_at"),
                SqlTypes.readRequiredInstant(rs, "updated_at"));
    }

    @Override
    public @NotNull CompletableFuture<EmailBinding> insert(@NotNull EmailBinding binding) {
        return sql.insertReturningKey("""
                        INSERT INTO emails (account_id, email, email_lower, verified, verified_at,
                                            is_primary, notify_login, notify_security,
                                            notify_newsletter, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                binding.accountId(), binding.email(), binding.emailLower(),
                binding.verified(), SqlTypes.toTimestamp(binding.verifiedAt()),
                binding.primary(), binding.notifyLogin(), binding.notifySecurity(),
                binding.notifyNewsletter(),
                SqlTypes.toTimestamp(binding.createdAt()), SqlTypes.toTimestamp(binding.updatedAt())
        ).thenApply(binding::withId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<EmailBinding>> findPrimary(long accountId) {
        return sql.queryOne(
                "SELECT " + COLUMNS + " FROM emails WHERE account_id = ? AND is_primary = 1 LIMIT 1",
                JdbcEmailRepository::map, accountId);
    }

    @Override
    public @NotNull CompletableFuture<List<EmailBinding>> findByAccount(long accountId) {
        return sql.queryList(
                "SELECT " + COLUMNS + " FROM emails WHERE account_id = ? ORDER BY is_primary DESC, id",
                JdbcEmailRepository::map, accountId);
    }

    @Override
    public @NotNull CompletableFuture<List<EmailBinding>> findByEmail(@NotNull String emailLower) {
        return sql.queryList("SELECT " + COLUMNS + " FROM emails WHERE email_lower = ?",
                JdbcEmailRepository::map, emailLower);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> markVerified(long bindingId, @NotNull Instant at) {
        return sql.update(
                "UPDATE emails SET verified = 1, verified_at = ? WHERE id = ? AND verified = 0",
                SqlTypes.toTimestamp(at), bindingId
        ).thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<Void> updateNotificationSettings(long bindingId,
                                                                       boolean notifyLogin,
                                                                       boolean notifySecurity,
                                                                       boolean notifyNewsletter) {
        return sql.update("""
                        UPDATE emails SET notify_login = ?, notify_security = ?, notify_newsletter = ?
                        WHERE id = ?
                        """,
                notifyLogin, notifySecurity, notifyNewsletter, bindingId
        ).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> setPrimary(long accountId, long bindingId) {
        // Обе операции в одной транзакции: между снятием флага и его установкой
        // аккаунт не должен оставаться без основного адреса.
        return sql.transaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE emails SET is_primary = 0 WHERE account_id = ?")) {
                statement.setLong(1, accountId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE emails SET is_primary = 1 WHERE id = ? AND account_id = ?")) {
                statement.setLong(1, bindingId);
                statement.setLong(2, accountId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(long bindingId) {
        return sql.update("DELETE FROM emails WHERE id = ?", bindingId)
                .thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<Integer> countAccountsByEmail(@NotNull String emailLower) {
        return sql.queryInt(
                "SELECT COUNT(DISTINCT account_id) FROM emails WHERE email_lower = ? AND verified = 1",
                0, emailLower);
    }
}
