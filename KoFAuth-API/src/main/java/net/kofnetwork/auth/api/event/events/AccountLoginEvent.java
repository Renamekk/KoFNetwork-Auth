package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Успешный вход.
 *
 * @param newDevice  вход выполнен с устройства, которого раньше не было. Подписчики
 *                   уведомлений реагируют именно на этот флаг: сообщать о каждом входе
 *                   значит приучить игрока не читать уведомления вовсе
 * @param newCountry вход из страны, из которой этот аккаунт раньше не заходил
 */
public record AccountLoginEvent(
        @NotNull Account account,
        @NotNull Session session,
        @NotNull AuthContext context,
        @Nullable TwoFactorMethod twoFactorUsed,
        boolean newDevice,
        boolean newCountry,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public AccountLoginEvent {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static @NotNull AccountLoginEvent of(@NotNull Account account,
                                                @NotNull Session session,
                                                @NotNull AuthContext context,
                                                @Nullable TwoFactorMethod twoFactorUsed,
                                                boolean newDevice,
                                                boolean newCountry) {
        return new AccountLoginEvent(account, session, context, twoFactorUsed,
                newDevice, newCountry, Instant.now());
    }

    @Override
    public @Nullable Long accountId() {
        return account.id();
    }

    /** Заслуживает ли вход уведомления владельцу. */
    public boolean isSuspicious() {
        return newDevice || newCountry;
    }
}
