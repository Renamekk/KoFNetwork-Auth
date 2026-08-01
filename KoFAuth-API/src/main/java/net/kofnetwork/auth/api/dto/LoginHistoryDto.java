package net.kofnetwork.auth.api.dto;

import net.kofnetwork.auth.api.model.EventSource;
import net.kofnetwork.auth.api.model.LoginAttempt;
import net.kofnetwork.auth.api.model.LoginResultType;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/** Строка истории входов для личного кабинета, {@code /auth logs} и ботов. */
public record LoginHistoryDto(
        boolean success,
        @NotNull LoginResultType result,
        @NotNull String ipMasked,
        @Nullable String country,
        @Nullable String city,
        @Nullable String isp,
        @NotNull EventSource source,
        @Nullable String server,
        @Nullable TwoFactorMethod twoFactorMethod,
        @NotNull Instant at
) {

    public LoginHistoryDto {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(ipMasked, "ipMasked");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(at, "at");
    }

    public static @NotNull LoginHistoryDto from(@NotNull LoginAttempt attempt) {
        return new LoginHistoryDto(
                attempt.success(),
                attempt.result(),
                attempt.ip().asMasked(),
                attempt.country(),
                attempt.city(),
                attempt.isp(),
                attempt.source(),
                attempt.server(),
                attempt.twoFactorMethod(),
                attempt.createdAt());
    }
}
