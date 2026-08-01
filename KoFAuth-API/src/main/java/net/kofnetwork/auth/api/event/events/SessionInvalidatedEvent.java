package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.event.AuthEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Одна или несколько сессий аккаунта перестали действовать.
 *
 * <p>Прокси, получив это событие, выкидывает игрока обратно в Limbo, если среди
 * отозванных оказалась его текущая игровая сессия. Именно поэтому событие несёт
 * список идентификаторов, а не только идентификатор аккаунта: при «выйти со всех
 * устройств, кроме текущего» выкинуть нужно всех, кроме одного.
 *
 * @param sessionPublicIds пустой список означает «все сессии аккаунта»
 */
public record SessionInvalidatedEvent(
        long accountIdValue,
        @NotNull List<String> sessionPublicIds,
        @NotNull String reason,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public SessionInvalidatedEvent {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");
        sessionPublicIds = sessionPublicIds == null ? List.of() : List.copyOf(sessionPublicIds);
    }

    public static @NotNull SessionInvalidatedEvent single(long accountId,
                                                          @NotNull String sessionPublicId,
                                                          @NotNull String reason) {
        return new SessionInvalidatedEvent(accountId, List.of(sessionPublicId), reason, Instant.now());
    }

    public static @NotNull SessionInvalidatedEvent all(long accountId, @NotNull String reason) {
        return new SessionInvalidatedEvent(accountId, List.of(), reason, Instant.now());
    }

    @Override
    public @Nullable Long accountId() {
        return accountIdValue;
    }

    /** Затронуты ли все сессии аккаунта. */
    public boolean affectsAll() {
        return sessionPublicIds.isEmpty();
    }

    /** Затронута ли конкретная сессия. */
    public boolean affects(@NotNull String publicId) {
        return affectsAll() || sessionPublicIds.contains(publicId);
    }
}
