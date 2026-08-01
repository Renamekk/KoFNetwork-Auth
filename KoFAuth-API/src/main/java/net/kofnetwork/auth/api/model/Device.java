package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Устройство, с которого заходили в аккаунт. Соответствует строке {@code devices}.
 *
 * <p><b>Отпечаток.</b> {@link #fingerprint()} — SHA-256 от стабильных характеристик клиента
 * (платформа, бренд клиента, ОС, версия протокола). IP в отпечаток намеренно не входит:
 * у большинства игроков он динамический, и включение адреса превратило бы каждое
 * переподключение в «новое устройство», обесценив само понятие доверенного устройства
 * и завалив игрока уведомлениями.
 *
 * <p><b>Доверие.</b> {@link #trusted()} снимает требование второго фактора. Помечать
 * устройство доверенным может только сам игрок и только после успешного прохождения 2FA.
 */
public record Device(
        long id,
        long accountId,
        @NotNull String fingerprint,
        @Nullable String displayName,
        @NotNull DevicePlatform platform,
        @Nullable String operatingSystem,
        @Nullable String browser,
        @Nullable String clientBrand,
        @Nullable Integer protocolVersion,
        @NotNull IpAddress firstSeenIp,
        @NotNull IpAddress lastSeenIp,
        @NotNull Instant firstSeenAt,
        @NotNull Instant lastSeenAt,
        boolean trusted,
        @Nullable Instant trustedAt,
        boolean blocked,
        @Nullable Instant blockedAt
) {

    public Device {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(firstSeenIp, "firstSeenIp");
        Objects.requireNonNull(lastSeenIp, "lastSeenIp");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        if (fingerprint.length() != 64) {
            throw new IllegalArgumentException(
                    "fingerprint должен быть SHA-256 в hex (64 символа), получено " + fingerprint.length());
        }
    }

    /** Создаёт запись о впервые увиденном устройстве. */
    public static @NotNull Device firstSeen(long accountId,
                                            @NotNull String fingerprint,
                                            @NotNull DevicePlatform platform,
                                            @NotNull IpAddress ip) {
        Instant now = Instant.now();
        return new Device(0L, accountId, fingerprint, null, platform,
                null, null, null, null,
                ip, ip, now, now,
                false, null, false, null);
    }

    /** Фиксирует очередное появление устройства. */
    public @NotNull Device seen(@NotNull Instant at, @NotNull IpAddress ip) {
        return new Device(id, accountId, fingerprint, displayName, platform,
                operatingSystem, browser, clientBrand, protocolVersion,
                firstSeenIp, ip, firstSeenAt, at,
                trusted, trustedAt, blocked, blockedAt);
    }

    /** Помечает устройство доверенным: со следующего входа второй фактор не спрашивается. */
    public @NotNull Device trust(@NotNull Instant at) {
        return new Device(id, accountId, fingerprint, displayName, platform,
                operatingSystem, browser, clientBrand, protocolVersion,
                firstSeenIp, lastSeenIp, firstSeenAt, lastSeenAt,
                true, at, blocked, blockedAt);
    }

    /** Снимает доверие. */
    public @NotNull Device untrust() {
        return new Device(id, accountId, fingerprint, displayName, platform,
                operatingSystem, browser, clientBrand, protocolVersion,
                firstSeenIp, lastSeenIp, firstSeenAt, lastSeenAt,
                false, null, blocked, blockedAt);
    }

    /** Блокирует устройство: вход с него запрещён вне зависимости от знания пароля. */
    public @NotNull Device block(@NotNull Instant at) {
        return new Device(id, accountId, fingerprint, displayName, platform,
                operatingSystem, browser, clientBrand, protocolVersion,
                firstSeenIp, lastSeenIp, firstSeenAt, lastSeenAt,
                false, null, true, at);
    }

    public @NotNull Device withId(long newId) {
        return new Device(newId, accountId, fingerprint, displayName, platform,
                operatingSystem, browser, clientBrand, protocolVersion,
                firstSeenIp, lastSeenIp, firstSeenAt, lastSeenAt,
                trusted, trustedAt, blocked, blockedAt);
    }

    public @NotNull Device withDisplayName(@Nullable String name) {
        return new Device(id, accountId, fingerprint, name, platform,
                operatingSystem, browser, clientBrand, protocolVersion,
                firstSeenIp, lastSeenIp, firstSeenAt, lastSeenAt,
                trusted, trustedAt, blocked, blockedAt);
    }

    /** Обогащает запись деталями клиента, известными только на платформенном уровне. */
    public @NotNull Device withClientDetails(@Nullable String os,
                                             @Nullable String browserName,
                                             @Nullable String brand,
                                             @Nullable Integer protocol) {
        return new Device(id, accountId, fingerprint, displayName, platform,
                os, browserName, brand, protocol,
                firstSeenIp, lastSeenIp, firstSeenAt, lastSeenAt,
                trusted, trustedAt, blocked, blockedAt);
    }

    /** Снимает ли устройство требование второго фактора. */
    public boolean skipsTwoFactor() {
        return trusted && !blocked;
    }

    /** Имя для показа игроку: пользовательское, иначе собранное из характеристик клиента. */
    public @NotNull String friendlyName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        StringBuilder sb = new StringBuilder();
        if (clientBrand != null && !clientBrand.isBlank()) {
            sb.append(clientBrand);
        } else if (browser != null && !browser.isBlank()) {
            sb.append(browser);
        } else {
            sb.append(platform.name());
        }
        if (operatingSystem != null && !operatingSystem.isBlank()) {
            sb.append(" / ").append(operatingSystem);
        }
        return sb.toString();
    }
}
