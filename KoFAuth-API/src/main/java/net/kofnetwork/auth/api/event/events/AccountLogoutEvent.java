package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.event.AuthEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Игрок вышел или его сессия завершена.
 *
 * @param reason одна из констант {@code Session.REASON_*}
 */
public record AccountLogoutEvent(
        @Nullable Long accountId,
        @NotNull String sessionPublicId,
        @NotNull String reason,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public AccountLogoutEvent {
        Objects.requireNonNull(sessionPublicId, "sessionPublicId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static @NotNull AccountLogoutEvent of(long accountId,
                                                 @NotNull String sessionPublicId,
                                                 @NotNull String reason) {
        return new AccountLogoutEvent(accountId, sessionPublicId, reason, Instant.now());
    }
}
