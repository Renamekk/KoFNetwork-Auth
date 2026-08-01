package net.kofnetwork.auth.api.result;

import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Исход попытки регистрации.
 *
 * @param passwordIssues при {@link RegistrationResultType#PASSWORD_TOO_WEAK} — список
 *                       кодов невыполненных требований ({@code TOO_SHORT}, {@code NO_DIGIT}...).
 *                       Игроку показывается весь список сразу: сообщать о требованиях
 *                       по одному за попытку — верный способ вывести человека из себя
 */
public record RegistrationResult(
        @NotNull RegistrationResultType type,
        @Nullable Account account,
        @Nullable Session session,
        @Nullable CaptchaChallenge captcha,
        @Nullable Duration retryAfter,
        @NotNull List<String> passwordIssues,
        @Nullable String detail
) {

    public RegistrationResult {
        Objects.requireNonNull(type, "type");
        passwordIssues = passwordIssues == null || passwordIssues.isEmpty()
                ? Collections.emptyList()
                : List.copyOf(passwordIssues);
    }

    /**
     * Аккаунт создан и сессия выдана сразу.
     *
     * <p>Отдельного входа после регистрации не требуется: игрок только что доказал
     * знание пароля, задав его.
     */
    public static @NotNull RegistrationResult success(@NotNull Account account, @NotNull Session session) {
        return new RegistrationResult(RegistrationResultType.SUCCESS, account, session,
                null, null, List.of(), null);
    }

    /** Пароль не прошёл политику сложности. */
    public static @NotNull RegistrationResult weakPassword(@NotNull List<String> issues) {
        return new RegistrationResult(RegistrationResultType.PASSWORD_TOO_WEAK, null, null,
                null, null, issues, null);
    }

    /** Перед созданием аккаунта нужно пройти CAPTCHA. */
    public static @NotNull RegistrationResult captchaRequired(@NotNull CaptchaChallenge challenge) {
        return new RegistrationResult(RegistrationResultType.CAPTCHA_REQUIRED, null, null,
                challenge, null, List.of(), null);
    }

    /** Превышен лимит запросов. */
    public static @NotNull RegistrationResult rateLimited(@NotNull Duration retryAfter) {
        return new RegistrationResult(RegistrationResultType.RATE_LIMITED, null, null,
                null, retryAfter, List.of(), null);
    }

    /** Отказ без дополнительного контекста. */
    public static @NotNull RegistrationResult rejected(@NotNull RegistrationResultType type,
                                                       @Nullable String detail) {
        return new RegistrationResult(type, null, null, null, null, List.of(), detail);
    }

    /** Внутренняя ошибка. */
    public static @NotNull RegistrationResult error(@Nullable String detail) {
        return new RegistrationResult(RegistrationResultType.ERROR, null, null,
                null, null, List.of(), detail);
    }

    public boolean isSuccess() {
        return type.isSuccess();
    }
}
