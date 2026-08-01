package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Привязка Discord. Соответствует строке {@code discord}.
 *
 * <p>OAuth2-токены в этом объекте отсутствуют намеренно. Они лежат в базе
 * зашифрованными и читаются исключительно {@code DiscordOAuthService}: доменной
 * модели, которая ходит по всем слоям и сериализуется в DTO, нечего их знать.
 *
 * @param discordId snowflake пользователя Discord
 */
public record DiscordBinding(
        long id,
        long accountId,
        long discordId,
        @Nullable String username,
        @Nullable String globalName,
        @Nullable String discriminator,
        @Nullable String avatarHash,
        boolean notificationsEnabled,
        boolean loginApprovalEnabled,
        @Nullable Instant oauthExpiresAt,
        @Nullable String oauthScopes,
        @NotNull Instant linkedAt,
        @NotNull Instant updatedAt
) {

    public DiscordBinding {
        Objects.requireNonNull(linkedAt, "linkedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static @NotNull DiscordBinding create(long accountId, long discordId) {
        Instant now = Instant.now();
        return new DiscordBinding(0L, accountId, discordId, null, null, null, null,
                true, false, null, null, now, now);
    }

    public @NotNull DiscordBinding withProfile(@Nullable String newUsername,
                                               @Nullable String newGlobalName,
                                               @Nullable String newDiscriminator,
                                               @Nullable String newAvatarHash) {
        return new DiscordBinding(id, accountId, discordId, newUsername, newGlobalName,
                newDiscriminator, newAvatarHash, notificationsEnabled, loginApprovalEnabled,
                oauthExpiresAt, oauthScopes, linkedAt, Instant.now());
    }

    public @NotNull DiscordBinding withNotifications(boolean enabled) {
        return new DiscordBinding(id, accountId, discordId, username, globalName,
                discriminator, avatarHash, enabled, loginApprovalEnabled,
                oauthExpiresAt, oauthScopes, linkedAt, Instant.now());
    }

    public @NotNull DiscordBinding withLoginApproval(boolean enabled) {
        return new DiscordBinding(id, accountId, discordId, username, globalName,
                discriminator, avatarHash, notificationsEnabled, enabled,
                oauthExpiresAt, oauthScopes, linkedAt, Instant.now());
    }

    public @NotNull DiscordBinding withOauth(@Nullable Instant expiresAt, @Nullable String scopes) {
        return new DiscordBinding(id, accountId, discordId, username, globalName,
                discriminator, avatarHash, notificationsEnabled, loginApprovalEnabled,
                expiresAt, scopes, linkedAt, Instant.now());
    }

    public @NotNull DiscordBinding withId(long newId) {
        return new DiscordBinding(newId, accountId, discordId, username, globalName,
                discriminator, avatarHash, notificationsEnabled, loginApprovalEnabled,
                oauthExpiresAt, oauthScopes, linkedAt, updatedAt);
    }

    /** Требуется ли обновить OAuth-токен. */
    public boolean needsOauthRefresh(@NotNull Instant at) {
        return oauthExpiresAt != null && !oauthExpiresAt.isAfter(at);
    }

    /** Отображаемое имя с учётом перехода Discord на глобальные имена. */
    public @NotNull String displayName() {
        if (globalName != null && !globalName.isBlank()) {
            return globalName;
        }
        if (username != null && !username.isBlank()) {
            // Legacy-дискриминатор "0" означает, что аккаунт уже переведён на новую схему.
            return discriminator == null || discriminator.isBlank() || "0".equals(discriminator)
                    ? username
                    : username + "#" + discriminator;
        }
        return String.valueOf(discordId);
    }
}
