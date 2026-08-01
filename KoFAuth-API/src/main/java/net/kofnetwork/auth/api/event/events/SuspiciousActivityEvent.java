package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.Severity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Обнаружена подозрительная активность: перебор, повторное использование токена,
 * бот, несовпадение IP сессии.
 *
 * <p>Подписчики этого события отвечают за реакцию: поднять требование CAPTCHA,
 * уведомить владельца, разбудить дежурного администратора. Само обнаружение
 * реакции не выполняет — иначе логика защиты расползлась бы по всем детекторам.
 */
public record SuspiciousActivityEvent(
        @Nullable Long accountId,
        @NotNull SecurityEventType type,
        @NotNull Severity severity,
        @NotNull AuthContext context,
        @Nullable String detail,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public SuspiciousActivityEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static @NotNull SuspiciousActivityEvent of(@Nullable Long accountId,
                                                      @NotNull SecurityEventType type,
                                                      @NotNull AuthContext context,
                                                      @Nullable String detail) {
        return new SuspiciousActivityEvent(accountId, type, type.defaultSeverity(),
                context, detail, Instant.now());
    }

    /** Требует ли событие немедленного уведомления владельца аккаунта. */
    public boolean requiresOwnerNotification() {
        return severity.notifiesOwner() && accountId != null;
    }
}
