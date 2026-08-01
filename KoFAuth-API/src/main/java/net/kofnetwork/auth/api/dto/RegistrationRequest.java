package net.kofnetwork.auth.api.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Запрос на регистрацию.
 *
 * <p>Подтверждение пароля ({@link #passwordConfirmation()}) проверяется на сервере, а не
 * только в клиенте: команда {@code /register} принимает оба аргумента, и сверка на
 * стороне сервера — единственная, которой можно доверять.
 *
 * @param email необязательная привязка почты сразу при регистрации
 */
public record RegistrationRequest(
        @NotNull String username,
        @NotNull String password,
        @NotNull String passwordConfirmation,
        @Nullable UUID playerUuid,
        @Nullable String email,
        @NotNull AuthContext context
) {

    public RegistrationRequest {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(passwordConfirmation, "passwordConfirmation");
        Objects.requireNonNull(context, "context");
    }

    public static @NotNull RegistrationRequest of(@NotNull String username,
                                                  @NotNull String password,
                                                  @NotNull String passwordConfirmation,
                                                  @NotNull AuthContext context) {
        return new RegistrationRequest(username, password, passwordConfirmation, null, null, context);
    }

    public static @NotNull RegistrationRequest ofPlayer(@NotNull UUID playerUuid,
                                                        @NotNull String username,
                                                        @NotNull String password,
                                                        @NotNull String passwordConfirmation,
                                                        @NotNull AuthContext context) {
        return new RegistrationRequest(username, password, passwordConfirmation, playerUuid, null, context);
    }

    /**
     * Совпадают ли пароль и подтверждение.
     *
     * <p>Сравнение обычное, а не в постоянном времени: оба значения только что пришли
     * от одного и того же клиента, и утечки по времени здесь неоткуда взяться —
     * секрет не сравнивается с хранимым значением.
     */
    public boolean passwordsMatch() {
        return password.equals(passwordConfirmation);
    }

    @Override
    public String toString() {
        return "RegistrationRequest{username='" + username + '\''
                + ", playerUuid=" + playerUuid
                + ", email=" + (email == null ? "null" : "<set>")
                + ", context=" + context
                + ", password=<redacted>}";
    }
}
