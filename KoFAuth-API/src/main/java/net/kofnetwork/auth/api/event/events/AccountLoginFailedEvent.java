package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.model.LoginResultType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Неудачная попытка входа.
 *
 * @param accountId       {@code null}, если такого ника не существует
 * @param usernameAttempt что именно было введено
 * @param failedInARow    сколько неудач подряд накопилось по этому аккаунту
 */
public record AccountLoginFailedEvent(
        @Nullable Long accountId,
        @NotNull String usernameAttempt,
        @NotNull LoginResultType result,
        @NotNull AuthContext context,
        int failedInARow,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public AccountLoginFailedEvent {
        Objects.requireNonNull(usernameAttempt, "usernameAttempt");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static @NotNull AccountLoginFailedEvent of(@Nullable Long accountId,
                                                      @NotNull String usernameAttempt,
                                                      @NotNull LoginResultType result,
                                                      @NotNull AuthContext context,
                                                      int failedInARow) {
        return new AccountLoginFailedEvent(accountId, usernameAttempt, result, context,
                failedInARow, Instant.now());
    }

    /**
     * Локальное событие: неудачная попытка обрабатывается тем же узлом, который её
     * получил, а рассылать на всю сеть каждую опечатку в пароле — заметный поток
     * сообщений без потребителя на удалённой стороне.
     */
    @Override
    public boolean isDistributed() {
        return false;
    }
}
