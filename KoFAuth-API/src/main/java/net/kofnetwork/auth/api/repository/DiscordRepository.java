package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.DiscordBinding;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Доступ к таблице {@code discord}. */
public interface DiscordRepository {

    @NotNull CompletableFuture<DiscordBinding> insert(@NotNull DiscordBinding binding);

    @NotNull CompletableFuture<Optional<DiscordBinding>> findByAccount(long accountId);

    @NotNull CompletableFuture<Optional<DiscordBinding>> findByDiscordId(long discordId);

    @NotNull CompletableFuture<DiscordBinding> update(@NotNull DiscordBinding binding);

    /**
     * Сохраняет OAuth2-токены.
     *
     * <p>Принимает открытые значения и шифрует их внутри реализации: вызывающий код не
     * должен знать ни ключа, ни алгоритма. Симметрично, {@link #findOauthTokens(long)}
     * расшифровывает.
     */
    @NotNull CompletableFuture<Void> updateOauthTokens(long accountId,
                                                       @Nullable String accessToken,
                                                       @Nullable String refreshToken,
                                                       @Nullable Instant expiresAt,
                                                       @Nullable String scopes);

    /** Читает и расшифровывает OAuth2-токены. */
    @NotNull CompletableFuture<Optional<OauthTokens>> findOauthTokens(long accountId);

    /** Расшифрованная пара токенов OAuth2. */
    record OauthTokens(@Nullable String accessToken,
                       @Nullable String refreshToken,
                       @Nullable Instant expiresAt,
                       @Nullable String scopes) {
    }

    @NotNull CompletableFuture<Boolean> deleteByAccount(long accountId);
}
