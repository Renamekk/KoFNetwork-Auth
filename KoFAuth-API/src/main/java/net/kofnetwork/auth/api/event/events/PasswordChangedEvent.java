package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.AuthEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Пароль аккаунта изменён.
 *
 * <p>Событие обязательно распределённое: узел, на котором пароль сменили (обычно
 * веб-API), не имеет прямого доступа к игровым сессиям на прокси. Именно по этому
 * событию прокси разрывает соединение игрока, если сессия была угнана.
 *
 * @param viaReset {@code true}, если пароль сброшен по токену из письма, а не изменён
 *                 владельцем с вводом старого пароля. Уведомление в этих случаях
 *                 разное: сброс — повод насторожиться сильнее
 */
public record PasswordChangedEvent(
        long accountIdValue,
        @NotNull AuthContext context,
        boolean viaReset,
        boolean sessionsRevoked,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public PasswordChangedEvent {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static @NotNull PasswordChangedEvent of(long accountId,
                                                   @NotNull AuthContext context,
                                                   boolean viaReset,
                                                   boolean sessionsRevoked) {
        return new PasswordChangedEvent(accountId, context, viaReset, sessionsRevoked, Instant.now());
    }

    @Override
    public @Nullable Long accountId() {
        return accountIdValue;
    }
}
