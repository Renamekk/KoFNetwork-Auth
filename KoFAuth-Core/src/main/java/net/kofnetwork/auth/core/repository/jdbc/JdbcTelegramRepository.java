package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.TelegramBinding;
import net.kofnetwork.auth.api.repository.TelegramRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link TelegramRepository} на JDBC. */
public final class JdbcTelegramRepository implements TelegramRepository {

    private static final String COLUMNS = """
            id, account_id, telegram_id, chat_id, username, first_name, last_name,
            language_code, notifications_enabled, login_approval_enabled, linked_at, updated_at
            """;

    private final SqlExecutor sql;

    public JdbcTelegramRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull TelegramBinding map(@NotNull ResultSet rs) throws SQLException {
        return new TelegramBinding(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getLong("telegram_id"),
                rs.getLong("chat_id"),
                rs.getString("username"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("language_code"),
                rs.getBoolean("notifications_enabled"),
                rs.getBoolean("login_approval_enabled"),
                SqlTypes.readRequiredInstant(rs, "linked_at"),
                SqlTypes.readRequiredInstant(rs, "updated_at"));
    }

    @Override
    public @NotNull CompletableFuture<TelegramBinding> insert(@NotNull TelegramBinding binding) {
        return sql.insertReturningKey("""
                        INSERT INTO telegram (account_id, telegram_id, chat_id, username, first_name,
                                              last_name, language_code, notifications_enabled,
                                              login_approval_enabled, linked_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                binding.accountId(), binding.telegramId(), binding.chatId(),
                binding.username(), binding.firstName(), binding.lastName(), binding.languageCode(),
                binding.notificationsEnabled(), binding.loginApprovalEnabled(),
                SqlTypes.toTimestamp(binding.linkedAt()), SqlTypes.toTimestamp(binding.updatedAt())
        ).thenApply(binding::withId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<TelegramBinding>> findByAccount(long accountId) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM telegram WHERE account_id = ?",
                JdbcTelegramRepository::map, accountId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<TelegramBinding>> findByTelegramId(long telegramId) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM telegram WHERE telegram_id = ?",
                JdbcTelegramRepository::map, telegramId);
    }

    @Override
    public @NotNull CompletableFuture<TelegramBinding> update(@NotNull TelegramBinding binding) {
        return sql.update("""
                        UPDATE telegram SET chat_id = ?, username = ?, first_name = ?, last_name = ?,
                                            language_code = ?, notifications_enabled = ?,
                                            login_approval_enabled = ?
                        WHERE id = ?
                        """,
                binding.chatId(), binding.username(), binding.firstName(), binding.lastName(),
                binding.languageCode(), binding.notificationsEnabled(),
                binding.loginApprovalEnabled(), binding.id()
        ).thenApply(ignored -> binding);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> deleteByAccount(long accountId) {
        return sql.update("DELETE FROM telegram WHERE account_id = ?", accountId)
                .thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<List<TelegramBinding>> findAllWithNotifications(int limit,
                                                                                      int offset) {
        return sql.queryList("""
                        SELECT %s FROM telegram WHERE notifications_enabled = 1
                        ORDER BY id LIMIT ? OFFSET ?
                        """.formatted(COLUMNS),
                JdbcTelegramRepository::map,
                Math.max(1, Math.min(limit, 1000)), Math.max(0, offset));
    }
}
