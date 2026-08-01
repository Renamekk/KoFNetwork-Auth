package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Сессия аккаунта. Соответствует строке таблицы {@code sessions}.
 *
 * <p><b>Два срока жизни.</b> {@link #expiresAt()} — скользящий: продлевается активностью
 * игрока. {@link #absoluteExpiresAt()} — жёсткий потолок, который не двигается никогда.
 * Без второго угнанная сессия жила бы вечно, пока злоумышленник её «прогревает»;
 * без первого игрока выкидывало бы посреди игры.
 *
 * <p><b>Где живёт.</b> Горячая копия — в Redis ({@code kofauth:session:{uuid}}), долговременная —
 * в MySQL. Redis отвечает на вопрос «пускать ли прямо сейчас», MySQL — на вопрос
 * «покажи мне все мои сессии» в личном кабинете.
 *
 * @param id                  первичный ключ, {@code 0} для несохранённой
 * @param accountId           владелец
 * @param deviceId            устройство, {@code null} если не опознано
 * @param publicId            внешний идентификатор, не связанный с автоинкрементом
 * @param type                канал создания
 * @param ip                  адрес на момент создания
 * @param userAgent           клиент
 * @param country             ISO 3166-1 alpha-2
 * @param city                город по геолокации
 * @param server              текущий сервер сети для игровых сессий
 * @param issuedAt            момент создания
 * @param lastSeenAt          последняя активность
 * @param expiresAt           скользящий срок
 * @param absoluteExpiresAt   жёсткий потолок
 * @param revoked             отозвана ли
 * @param revokedAt           когда отозвана
 * @param revokedReason       почему отозвана
 */
public record Session(
        long id,
        long accountId,
        @Nullable Long deviceId,
        @NotNull String publicId,
        @NotNull SessionType type,
        @NotNull IpAddress ip,
        @Nullable String userAgent,
        @Nullable String country,
        @Nullable String city,
        @Nullable String server,
        @NotNull Instant issuedAt,
        @NotNull Instant lastSeenAt,
        @NotNull Instant expiresAt,
        @NotNull Instant absoluteExpiresAt,
        boolean revoked,
        @Nullable Instant revokedAt,
        @Nullable String revokedReason
) {

    /** Причина отзыва: обычный выход игрока. */
    public static final String REASON_LOGOUT = "LOGOUT";
    /** Причина отзыва: сменён пароль — все прочие сессии недействительны. */
    public static final String REASON_PASSWORD_CHANGED = "PASSWORD_CHANGED";
    /** Причина отзыва: действие администратора. */
    public static final String REASON_ADMIN = "ADMIN";
    /** Причина отзыва: истёк срок. */
    public static final String REASON_TIMEOUT = "TIMEOUT";
    /** Причина отзыва: адрес перестал совпадать с зафиксированным. */
    public static final String REASON_IP_MISMATCH = "IP_MISMATCH";
    /** Причина отзыва: игрок вышел со всех устройств. */
    public static final String REASON_LOGOUT_ALL = "LOGOUT_ALL";

    public Session {
        Objects.requireNonNull(publicId, "publicId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        if (absoluteExpiresAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException(
                    "absoluteExpiresAt не может быть раньше expiresAt: жёсткий потолок обязан быть не меньше скользящего срока");
        }
    }

    /**
     * Создаёт новую сессию.
     *
     * @param slidingTtl  скользящий срок (например, сутки)
     * @param absoluteTtl жёсткий потолок (например, неделя)
     */
    public static @NotNull Session create(long accountId,
                                          @Nullable Long deviceId,
                                          @NotNull SessionType type,
                                          @NotNull IpAddress ip,
                                          @Nullable String userAgent,
                                          @NotNull Duration slidingTtl,
                                          @NotNull Duration absoluteTtl) {
        Instant now = Instant.now();
        Duration absolute = absoluteTtl.compareTo(slidingTtl) < 0 ? slidingTtl : absoluteTtl;
        return new Session(
                0L,
                accountId,
                deviceId,
                UUID.randomUUID().toString(),
                type,
                ip,
                userAgent,
                null,
                null,
                null,
                now,
                now,
                now.plus(slidingTtl),
                now.plus(absolute),
                false,
                null,
                null);
    }

    /** Действует ли сессия на указанный момент. */
    public boolean isValid(@NotNull Instant at) {
        return !revoked
                && expiresAt.isAfter(at)
                && absoluteExpiresAt.isAfter(at);
    }

    /** Истёк ли срок (без учёта отзыва). */
    public boolean isExpired(@NotNull Instant at) {
        return !expiresAt.isAfter(at) || !absoluteExpiresAt.isAfter(at);
    }

    /**
     * Продлевает сессию активностью.
     *
     * <p>Новый скользящий срок не может превысить {@link #absoluteExpiresAt()} —
     * иначе потолок перестал бы быть потолком.
     *
     * @param at         момент активности
     * @param slidingTtl на сколько продлевать от {@code at}
     */
    public @NotNull Session touch(@NotNull Instant at, @NotNull Duration slidingTtl) {
        Instant extended = at.plus(slidingTtl);
        Instant capped = extended.isAfter(absoluteExpiresAt) ? absoluteExpiresAt : extended;
        return new Session(id, accountId, deviceId, publicId, type, ip, userAgent, country, city,
                server, issuedAt, at, capped, absoluteExpiresAt, revoked, revokedAt, revokedReason);
    }

    /** Отзывает сессию. Повторный отзыв не меняет исходную причину и момент. */
    public @NotNull Session revoke(@NotNull Instant at, @NotNull String reason) {
        if (revoked) {
            return this;
        }
        return new Session(id, accountId, deviceId, publicId, type, ip, userAgent, country, city,
                server, issuedAt, lastSeenAt, expiresAt, absoluteExpiresAt, true, at, reason);
    }

    /** Фиксирует переход игрока на другой сервер сети. */
    public @NotNull Session withServer(@Nullable String newServer) {
        return new Session(id, accountId, deviceId, publicId, type, ip, userAgent, country, city,
                newServer, issuedAt, lastSeenAt, expiresAt, absoluteExpiresAt, revoked, revokedAt, revokedReason);
    }

    /** Дополняет сессию геоданными, полученными асинхронно уже после создания. */
    public @NotNull Session withGeo(@Nullable String newCountry, @Nullable String newCity) {
        return new Session(id, accountId, deviceId, publicId, type, ip, userAgent, newCountry, newCity,
                server, issuedAt, lastSeenAt, expiresAt, absoluteExpiresAt, revoked, revokedAt, revokedReason);
    }

    /** Проставляет первичный ключ после вставки в базу. */
    public @NotNull Session withId(long newId) {
        return new Session(newId, accountId, deviceId, publicId, type, ip, userAgent, country, city,
                server, issuedAt, lastSeenAt, expiresAt, absoluteExpiresAt, revoked, revokedAt, revokedReason);
    }

    /** Совпадает ли адрес с зафиксированным при создании. */
    public boolean matchesIp(@NotNull IpAddress candidate) {
        return ip.equals(candidate);
    }
}
