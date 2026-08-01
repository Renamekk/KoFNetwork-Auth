package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Запись в истории входов. Соответствует строке {@code login_history}.
 *
 * <p>Пишется <b>всегда</b> — и при успехе, и при неудаче, и даже когда введённого ника
 * не существует ({@link #accountId()} равен {@code null}). Именно неудачные записи
 * с несуществующими никами дают картину перебора имён, которую иначе неоткуда взять.
 *
 * @param usernameAttempt ровно то, что ввёл клиент, до нормализации — по расхождению
 *                        регистра и опечаткам видно, работает человек или скрипт
 */
public record LoginAttempt(
        long id,
        @Nullable Long accountId,
        @Nullable Long deviceId,
        @NotNull String usernameAttempt,
        boolean success,
        @NotNull LoginResultType result,
        @NotNull IpAddress ip,
        @Nullable String country,
        @Nullable String city,
        @Nullable String isp,
        @Nullable String userAgent,
        @NotNull EventSource source,
        @Nullable String server,
        @Nullable Integer protocolVersion,
        @Nullable TwoFactorMethod twoFactorMethod,
        @NotNull Instant createdAt
) {

    public LoginAttempt {
        Objects.requireNonNull(usernameAttempt, "usernameAttempt");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Успешный вход. */
    public static @NotNull LoginAttempt success(long accountId,
                                                @Nullable Long deviceId,
                                                @NotNull String username,
                                                @NotNull IpAddress ip,
                                                @NotNull EventSource source,
                                                @Nullable TwoFactorMethod twoFactor) {
        return new LoginAttempt(0L, accountId, deviceId, username, true, LoginResultType.SUCCESS,
                ip, null, null, null, null, source, null, null, twoFactor, Instant.now());
    }

    /** Неудачный вход. {@code accountId} равен {@code null}, если такого ника нет. */
    public static @NotNull LoginAttempt failure(@Nullable Long accountId,
                                                @NotNull String username,
                                                @NotNull LoginResultType result,
                                                @NotNull IpAddress ip,
                                                @NotNull EventSource source) {
        return new LoginAttempt(0L, accountId, null, username, false, result,
                ip, null, null, null, null, source, null, null, null, Instant.now());
    }

    /** Дополняет запись геоданными, полученными асинхронно. */
    public @NotNull LoginAttempt withGeo(@Nullable String newCountry,
                                         @Nullable String newCity,
                                         @Nullable String newIsp) {
        return new LoginAttempt(id, accountId, deviceId, usernameAttempt, success, result,
                ip, newCountry, newCity, newIsp, userAgent, source, server, protocolVersion,
                twoFactorMethod, createdAt);
    }

    /** Дополняет запись контекстом клиента. */
    public @NotNull LoginAttempt withClient(@Nullable String newUserAgent,
                                            @Nullable String newServer,
                                            @Nullable Integer newProtocolVersion) {
        return new LoginAttempt(id, accountId, deviceId, usernameAttempt, success, result,
                ip, country, city, isp, newUserAgent, source, newServer, newProtocolVersion,
                twoFactorMethod, createdAt);
    }

    public @NotNull LoginAttempt withDeviceId(@Nullable Long newDeviceId) {
        return new LoginAttempt(id, accountId, newDeviceId, usernameAttempt, success, result,
                ip, country, city, isp, userAgent, source, server, protocolVersion,
                twoFactorMethod, createdAt);
    }
}
