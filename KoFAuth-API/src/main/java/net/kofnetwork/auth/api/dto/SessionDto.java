package net.kofnetwork.auth.api.dto;

import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Сессия в представлении для личного кабинета и команды {@code /auth sessions}.
 *
 * @param current помечает сессию, из которой пришёл текущий запрос: в интерфейсе она
 *                подписывается «это устройство», и кнопка «завершить» для неё
 *                означает выход, а не отзыв чужой сессии
 */
public record SessionDto(
        @NotNull String publicId,
        @NotNull SessionType type,
        @NotNull String ipMasked,
        @Nullable String userAgent,
        @Nullable String country,
        @Nullable String city,
        @Nullable String server,
        @Nullable String deviceName,
        @NotNull Instant issuedAt,
        @NotNull Instant lastSeenAt,
        @NotNull Instant expiresAt,
        boolean current,
        boolean revoked
) {

    public SessionDto {
        Objects.requireNonNull(publicId, "publicId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(ipMasked, "ipMasked");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public static @NotNull SessionDto from(@NotNull Session session,
                                           @Nullable String deviceName,
                                           boolean current) {
        return new SessionDto(
                session.publicId(),
                session.type(),
                session.ip().asMasked(),
                session.userAgent(),
                session.country(),
                session.city(),
                session.server(),
                deviceName,
                session.issuedAt(),
                session.lastSeenAt(),
                session.expiresAt(),
                current,
                session.revoked());
    }
}
