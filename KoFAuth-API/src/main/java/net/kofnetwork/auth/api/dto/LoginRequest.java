package net.kofnetwork.auth.api.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Запрос на вход.
 *
 * <p><b>О пароле в виде {@link String}.</b> Канонический совет — держать пароль в
 * {@code char[]} и затирать после использования. Здесь он не применяется сознательно:
 * пароль приходит из аргумента команды Minecraft или из тела JSON-запроса, то есть
 * String уже создан платформой до того, как мы получили управление, и наш собственный
 * {@code char[]} не уменьшит число копий в куче. Реальную защиту дают другие меры:
 * пароль не логируется ({@link #toString()} его скрывает), не попадает в аудит и живёт
 * в памяти доли секунды до вызова BCrypt.
 *
 * @param twoFactorCode код TOTP или резервный код, если игрок вводит их сразу вместе
 *                      с паролем; {@code null} при обычном двухшаговом входе
 */
public record LoginRequest(
        @NotNull String username,
        @NotNull String password,
        @Nullable UUID playerUuid,
        @Nullable String twoFactorCode,
        @NotNull AuthContext context
) {

    public LoginRequest {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(context, "context");
    }

    public static @NotNull LoginRequest of(@NotNull String username,
                                           @NotNull String password,
                                           @NotNull AuthContext context) {
        return new LoginRequest(username, password, null, null, context);
    }

    public static @NotNull LoginRequest ofPlayer(@NotNull UUID playerUuid,
                                                 @NotNull String username,
                                                 @NotNull String password,
                                                 @NotNull AuthContext context) {
        return new LoginRequest(username, password, playerUuid, null, context);
    }

    public @NotNull LoginRequest withTwoFactorCode(@Nullable String code) {
        return new LoginRequest(username, password, playerUuid, code, context);
    }

    /** Пароль и код второго фактора скрыты. */
    @Override
    public String toString() {
        return "LoginRequest{username='" + username + '\''
                + ", playerUuid=" + playerUuid
                + ", context=" + context
                + ", password=<redacted>"
                + ", twoFactorCode=" + (twoFactorCode == null ? "null" : "<redacted>") + '}';
    }
}
