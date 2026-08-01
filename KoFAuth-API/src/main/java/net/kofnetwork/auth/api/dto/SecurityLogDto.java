package net.kofnetwork.auth.api.dto;

import net.kofnetwork.auth.api.model.EventSource;
import net.kofnetwork.auth.api.model.SecurityLogEntry;
import net.kofnetwork.auth.api.model.Severity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Запись аудита для внешнего показа.
 *
 * <p>{@code metadata} из {@link SecurityLogEntry} сюда не переносится: это свободный
 * JSON, наполняемый разными участками кода, и гарантировать, что в нём не окажется
 * внутренних идентификаторов или деталей реализации, невозможно. Наружу идёт только
 * человекочитаемое {@link #message()}.
 */
public record SecurityLogDto(
        @NotNull String eventType,
        @NotNull Severity severity,
        @NotNull EventSource source,
        @Nullable String ipMasked,
        @Nullable String country,
        @Nullable String city,
        @Nullable String message,
        boolean byAdmin,
        @NotNull Instant at
) {

    public SecurityLogDto {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(at, "at");
    }

    public static @NotNull SecurityLogDto from(@NotNull SecurityLogEntry entry) {
        return new SecurityLogDto(
                entry.eventType(),
                entry.severity(),
                entry.source(),
                entry.ip() == null ? null : entry.ip().asMasked(),
                entry.country(),
                entry.city(),
                entry.message(),
                entry.actorId() != null,
                entry.createdAt());
    }
}
