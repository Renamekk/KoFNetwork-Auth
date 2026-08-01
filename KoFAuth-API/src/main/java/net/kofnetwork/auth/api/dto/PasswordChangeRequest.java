package net.kofnetwork.auth.api.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Запрос на смену пароля.
 *
 * <p>Текущий пароль обязателен, даже когда игрок уже аутентифицирован: сессия могла
 * быть угнана, и без повторного подтверждения знания пароля угон превращается в
 * полный захват аккаунта. Исключение — сброс по токену из письма, для которого
 * предусмотрен отдельный метод сервиса.
 *
 * @param revokeOtherSessions завершить ли остальные сессии. По умолчанию {@code true}:
 *                            если пароль меняют из-за подозрения на взлом, оставлять
 *                            злоумышленнику действующую сессию бессмысленно
 */
public record PasswordChangeRequest(
        @NotNull String currentPassword,
        @NotNull String newPassword,
        @NotNull String newPasswordConfirmation,
        boolean revokeOtherSessions,
        @Nullable String twoFactorCode,
        @NotNull AuthContext context
) {

    public PasswordChangeRequest {
        Objects.requireNonNull(currentPassword, "currentPassword");
        Objects.requireNonNull(newPassword, "newPassword");
        Objects.requireNonNull(newPasswordConfirmation, "newPasswordConfirmation");
        Objects.requireNonNull(context, "context");
    }

    public static @NotNull PasswordChangeRequest of(@NotNull String currentPassword,
                                                    @NotNull String newPassword,
                                                    @NotNull String confirmation,
                                                    @NotNull AuthContext context) {
        return new PasswordChangeRequest(currentPassword, newPassword, confirmation, true, null, context);
    }

    public boolean passwordsMatch() {
        return newPassword.equals(newPasswordConfirmation);
    }

    /** Совпадает ли новый пароль со старым — такую смену принимать не следует. */
    public boolean isSameAsCurrent() {
        return currentPassword.equals(newPassword);
    }

    @Override
    public String toString() {
        return "PasswordChangeRequest{revokeOtherSessions=" + revokeOtherSessions
                + ", context=" + context
                + ", passwords=<redacted>}";
    }
}
