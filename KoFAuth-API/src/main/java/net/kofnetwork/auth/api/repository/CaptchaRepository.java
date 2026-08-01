package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.CaptchaChallenge;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ к таблице {@code captcha}.
 *
 * <p>Активный челлендж дублируется в Redis: проверка ответа происходит в горячем пути,
 * а MySQL здесь нужен для статистики и для того, чтобы прогресс не терялся при
 * перезапуске Limbo.
 */
public interface CaptchaRepository {

    @NotNull CompletableFuture<CaptchaChallenge> insert(@NotNull CaptchaChallenge challenge);

    @NotNull CompletableFuture<Optional<CaptchaChallenge>> findByChallengeId(@NotNull String challengeId);

    /** Незавершённый челлендж игрока, если он есть. */
    @NotNull CompletableFuture<Optional<CaptchaChallenge>> findPendingByPlayer(@NotNull UUID playerUuid,
                                                                               @NotNull Instant at);

    @NotNull CompletableFuture<CaptchaChallenge> update(@NotNull CaptchaChallenge challenge);

    /** Помечает просроченные челленджи. */
    @NotNull CompletableFuture<Integer> expireOverdue(@NotNull Instant at);

    /** Удаляет завершённые челленджи старше указанного момента. */
    @NotNull CompletableFuture<Integer> deleteResolvedBefore(@NotNull Instant before);
}
