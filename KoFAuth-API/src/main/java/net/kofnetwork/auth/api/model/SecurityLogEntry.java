package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Запись аудита. Соответствует строке {@code security_logs}.
 *
 * <p>Отличие от {@link LoginAttempt}: там фиксируются только попытки входа, здесь —
 * любое значимое действие с аккаунтом, включая изменения привязок, смену пароля и
 * действия администраторов.
 *
 * <p>{@link #metadata()} сохраняется в колонку JSON. Класть туда пароли, коды и токены
 * запрещено: журнал аудита читают модераторы, а его выгрузка — рядовая операция.
 *
 * @param actorId кто выполнил действие, если это не сам владелец (администратор)
 */
public record SecurityLogEntry(
        long id,
        @Nullable Long accountId,
        @Nullable Long actorId,
        @NotNull String eventType,
        @NotNull Severity severity,
        @NotNull EventSource source,
        @Nullable IpAddress ip,
        @Nullable String country,
        @Nullable String city,
        @Nullable String userAgent,
        @Nullable String message,
        @NotNull Map<String, Object> metadata,
        @NotNull Instant createdAt
) {

    public SecurityLogEntry {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        metadata = metadata == null || metadata.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** Запись со значениями по умолчанию: важность берётся из типа события. */
    public static @NotNull SecurityLogEntry of(@Nullable Long accountId,
                                               @NotNull SecurityEventType type,
                                               @NotNull EventSource source,
                                               @Nullable IpAddress ip,
                                               @Nullable String message) {
        return new SecurityLogEntry(0L, accountId, null, type.name(), type.defaultSeverity(),
                source, ip, null, null, null, message, Map.of(), Instant.now());
    }

    /** Запись о действии администратора над чужим аккаунтом. */
    public static @NotNull SecurityLogEntry byAdmin(long targetAccountId,
                                                    long adminAccountId,
                                                    @NotNull SecurityEventType type,
                                                    @NotNull EventSource source,
                                                    @Nullable String message) {
        return new SecurityLogEntry(0L, targetAccountId, adminAccountId, type.name(),
                type.defaultSeverity(), source, null, null, null, null, message,
                Map.of(), Instant.now());
    }

    public @NotNull SecurityLogEntry withMetadata(@NotNull Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.putAll(extra);
        return new SecurityLogEntry(id, accountId, actorId, eventType, severity, source,
                ip, country, city, userAgent, message, merged, createdAt);
    }

    public @NotNull SecurityLogEntry withSeverity(@NotNull Severity newSeverity) {
        return new SecurityLogEntry(id, accountId, actorId, eventType, newSeverity, source,
                ip, country, city, userAgent, message, metadata, createdAt);
    }

    public @NotNull SecurityLogEntry withGeo(@Nullable String newCountry, @Nullable String newCity) {
        return new SecurityLogEntry(id, accountId, actorId, eventType, severity, source,
                ip, newCountry, newCity, userAgent, message, metadata, createdAt);
    }

    public @NotNull SecurityLogEntry withUserAgent(@Nullable String newUserAgent) {
        return new SecurityLogEntry(id, accountId, actorId, eventType, severity, source,
                ip, country, city, newUserAgent, message, metadata, createdAt);
    }
}
