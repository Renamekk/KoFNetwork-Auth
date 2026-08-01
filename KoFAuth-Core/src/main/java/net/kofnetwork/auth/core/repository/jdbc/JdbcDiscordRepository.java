package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.DiscordBinding;
import net.kofnetwork.auth.api.repository.DiscordRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import net.kofnetwork.auth.core.security.AesCipher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link DiscordRepository} на JDBC.
 *
 * <p>Шифрование OAuth-токенов выполняется здесь: наружу и внутрь идут открытые
 * строки, в базу — результат AES-256-GCM. Сервисный слой о ключе не знает, а дамп
 * базы без ключа не даёт доступа к аккаунтам Discord игроков.
 */
public final class JdbcDiscordRepository implements DiscordRepository {

    private static final String COLUMNS = """
            id, account_id, discord_id, username, global_name, discriminator, avatar_hash,
            notifications_enabled, login_approval_enabled, oauth_expires_at, oauth_scopes,
            linked_at, updated_at
            """;

    private final SqlExecutor sql;
    private final AesCipher cipher;

    public JdbcDiscordRepository(@NotNull SqlExecutor sql, @NotNull AesCipher cipher) {
        this.sql = sql;
        this.cipher = cipher;
    }

    static @NotNull DiscordBinding map(@NotNull ResultSet rs) throws SQLException {
        return new DiscordBinding(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getLong("discord_id"),
                rs.getString("username"),
                rs.getString("global_name"),
                rs.getString("discriminator"),
                rs.getString("avatar_hash"),
                rs.getBoolean("notifications_enabled"),
                rs.getBoolean("login_approval_enabled"),
                SqlTypes.readInstant(rs, "oauth_expires_at"),
                rs.getString("oauth_scopes"),
                SqlTypes.readRequiredInstant(rs, "linked_at"),
                SqlTypes.readRequiredInstant(rs, "updated_at"));
    }

    @Override
    public @NotNull CompletableFuture<DiscordBinding> insert(@NotNull DiscordBinding binding) {
        return sql.insertReturningKey("""
                        INSERT INTO discord (account_id, discord_id, username, global_name,
                                             discriminator, avatar_hash, notifications_enabled,
                                             login_approval_enabled, linked_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                binding.accountId(), binding.discordId(), binding.username(), binding.globalName(),
                binding.discriminator(), binding.avatarHash(), binding.notificationsEnabled(),
                binding.loginApprovalEnabled(),
                SqlTypes.toTimestamp(binding.linkedAt()), SqlTypes.toTimestamp(binding.updatedAt())
        ).thenApply(binding::withId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<DiscordBinding>> findByAccount(long accountId) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM discord WHERE account_id = ?",
                JdbcDiscordRepository::map, accountId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<DiscordBinding>> findByDiscordId(long discordId) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM discord WHERE discord_id = ?",
                JdbcDiscordRepository::map, discordId);
    }

    @Override
    public @NotNull CompletableFuture<DiscordBinding> update(@NotNull DiscordBinding binding) {
        return sql.update("""
                        UPDATE discord SET username = ?, global_name = ?, discriminator = ?,
                                           avatar_hash = ?, notifications_enabled = ?,
                                           login_approval_enabled = ?
                        WHERE id = ?
                        """,
                binding.username(), binding.globalName(), binding.discriminator(),
                binding.avatarHash(), binding.notificationsEnabled(),
                binding.loginApprovalEnabled(), binding.id()
        ).thenApply(ignored -> binding);
    }

    @Override
    public @NotNull CompletableFuture<Void> updateOauthTokens(long accountId,
                                                              @Nullable String accessToken,
                                                              @Nullable String refreshToken,
                                                              @Nullable Instant expiresAt,
                                                              @Nullable String scopes) {
        return sql.update("""
                        UPDATE discord SET oauth_access_token = ?, oauth_refresh_token = ?,
                                           oauth_expires_at = ?, oauth_scopes = ?
                        WHERE account_id = ?
                        """,
                accessToken == null ? null : cipher.encrypt(accessToken),
                refreshToken == null ? null : cipher.encrypt(refreshToken),
                SqlTypes.toTimestamp(expiresAt),
                scopes,
                accountId
        ).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Optional<OauthTokens>> findOauthTokens(long accountId) {
        return sql.queryOne("""
                        SELECT oauth_access_token, oauth_refresh_token, oauth_expires_at, oauth_scopes
                        FROM discord WHERE account_id = ?
                        """,
                rs -> new OauthTokens(
                        // decryptToStringOrNull, а не decrypt: одна испорченная запись
                        // не должна ломать выборку. Игрок просто пройдёт OAuth заново.
                        cipher.decryptToStringOrNull(rs.getBytes("oauth_access_token")),
                        cipher.decryptToStringOrNull(rs.getBytes("oauth_refresh_token")),
                        SqlTypes.readInstant(rs, "oauth_expires_at"),
                        rs.getString("oauth_scopes")),
                accountId);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> deleteByAccount(long accountId) {
        return sql.update("DELETE FROM discord WHERE account_id = ?", accountId)
                .thenApply(affected -> affected > 0);
    }
}
