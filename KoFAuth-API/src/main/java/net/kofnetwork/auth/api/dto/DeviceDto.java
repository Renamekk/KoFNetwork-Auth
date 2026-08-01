package net.kofnetwork.auth.api.dto;

import net.kofnetwork.auth.api.model.Device;
import net.kofnetwork.auth.api.model.DevicePlatform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Устройство в представлении для личного кабинета.
 *
 * <p>{@link #shortFingerprint()} — первые 12 символов отпечатка. Полный отпечаток наружу
 * не отдаётся: он используется как ключ поиска устройства, и знание чужого отпечатка
 * упрощает подделку «доверенного» устройства.
 */
public record DeviceDto(
        @NotNull String shortFingerprint,
        @NotNull String name,
        @NotNull DevicePlatform platform,
        @Nullable String operatingSystem,
        @Nullable String browser,
        @NotNull String lastSeenIpMasked,
        @NotNull Instant firstSeenAt,
        @NotNull Instant lastSeenAt,
        boolean trusted,
        boolean blocked,
        boolean current
) {

    public DeviceDto {
        Objects.requireNonNull(shortFingerprint, "shortFingerprint");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(lastSeenIpMasked, "lastSeenIpMasked");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }

    public static @NotNull DeviceDto from(@NotNull Device device, boolean current) {
        return new DeviceDto(
                device.fingerprint().substring(0, 12),
                device.friendlyName(),
                device.platform(),
                device.operatingSystem(),
                device.browser(),
                device.lastSeenIp().asMasked(),
                device.firstSeenAt(),
                device.lastSeenAt(),
                device.trusted(),
                device.blocked(),
                current);
    }
}
