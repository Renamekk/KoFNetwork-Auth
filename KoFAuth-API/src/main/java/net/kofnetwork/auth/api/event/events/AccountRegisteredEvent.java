package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.model.Account;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/** Создан новый аккаунт. */
public record AccountRegisteredEvent(
        @NotNull Account account,
        @NotNull AuthContext context,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public AccountRegisteredEvent {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static @NotNull AccountRegisteredEvent of(@NotNull Account account, @NotNull AuthContext context) {
        return new AccountRegisteredEvent(account, context, Instant.now());
    }

    @Override
    public @Nullable Long accountId() {
        return account.id();
    }
}
