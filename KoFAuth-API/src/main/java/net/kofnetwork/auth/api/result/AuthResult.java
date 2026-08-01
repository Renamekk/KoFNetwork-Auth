package net.kofnetwork.auth.api.result;

import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.LoginResultType;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * Исход попытки аутентификации.
 *
 * <p>Один тип на все исходы вместо исключений: вызывающий код (команда {@code /login},
 * REST-контроллер, обработчик Telegram) разбирает {@link #type()} в {@code switch} и
 * получает от компилятора гарантию, что ни один исход не забыт.
 *
 * <p>Поля заполняются в зависимости от исхода:
 * <ul>
 *   <li>{@link LoginResultType#SUCCESS} — {@link #account()} и {@link #session()};</li>
 *   <li>{@link LoginResultType#TWO_FACTOR_REQUIRED} — {@link #account()} и
 *       {@link #requiredTwoFactor()}, сессии ещё нет;</li>
 *   <li>{@link LoginResultType#CAPTCHA_REQUIRED} — {@link #captcha()};</li>
 *   <li>{@link LoginResultType#RATE_LIMITED} и {@link LoginResultType#TEMPORARILY_LOCKED} —
 *       {@link #retryAfter()};</li>
 *   <li>{@link LoginResultType#BAD_PASSWORD} — {@link #remainingAttempts()}.</li>
 * </ul>
 *
 * @param approvalToken токен подтверждения входа для Telegram и Discord: по нему бот
 *                      находит ожидающий запрос, когда игрок нажимает кнопку
 */
public record AuthResult(
        @NotNull LoginResultType type,
        @Nullable Account account,
        @Nullable Session session,
        @Nullable TwoFactorMethod requiredTwoFactor,
        @Nullable CaptchaChallenge captcha,
        @Nullable Duration retryAfter,
        int remainingAttempts,
        @Nullable String approvalToken,
        @Nullable String detail
) {

    public AuthResult {
        Objects.requireNonNull(type, "type");
    }

    /** Успешный вход. */
    public static @NotNull AuthResult success(@NotNull Account account, @NotNull Session session) {
        return new AuthResult(LoginResultType.SUCCESS, account, session,
                null, null, null, 0, null, null);
    }

    /**
     * Пароль верен, но нужен второй фактор.
     *
     * @param approvalToken для {@link TwoFactorMethod#TELEGRAM} и
     *                      {@link TwoFactorMethod#DISCORD}; для TOTP — {@code null}
     */
    public static @NotNull AuthResult twoFactorRequired(@NotNull Account account,
                                                        @NotNull TwoFactorMethod method,
                                                        @Nullable String approvalToken) {
        return new AuthResult(LoginResultType.TWO_FACTOR_REQUIRED, account, null,
                method, null, null, 0, approvalToken, null);
    }

    /** Требуется пройти CAPTCHA. */
    public static @NotNull AuthResult captchaRequired(@Nullable Account account,
                                                      @NotNull CaptchaChallenge challenge) {
        return new AuthResult(LoginResultType.CAPTCHA_REQUIRED, account, null,
                null, challenge, null, 0, null, null);
    }

    /**
     * Неверный пароль.
     *
     * @param remainingAttempts сколько попыток осталось до временной блокировки
     */
    public static @NotNull AuthResult badPassword(int remainingAttempts) {
        return new AuthResult(LoginResultType.BAD_PASSWORD, null, null,
                null, null, null, remainingAttempts, null, null);
    }

    /**
     * Аккаунта не существует.
     *
     * <p>Наружу этот исход обязан выглядеть так же, как {@link #badPassword(int)}:
     * различие в ответе превращает форму входа в средство проверки существования ника.
     */
    public static @NotNull AuthResult unknownAccount() {
        return new AuthResult(LoginResultType.UNKNOWN_ACCOUNT, null, null,
                null, null, null, 0, null, null);
    }

    /** Превышен лимит попыток. */
    public static @NotNull AuthResult rateLimited(@NotNull Duration retryAfter) {
        return new AuthResult(LoginResultType.RATE_LIMITED, null, null,
                null, null, retryAfter, 0, null, null);
    }

    /** Временная блокировка после серии неудач. */
    public static @NotNull AuthResult temporarilyLocked(@NotNull Duration retryAfter) {
        return new AuthResult(LoginResultType.TEMPORARILY_LOCKED, null, null,
                null, null, retryAfter, 0, null, null);
    }

    /** Отказ без дополнительного контекста: бан, блокировка администратором, AntiBot. */
    public static @NotNull AuthResult rejected(@NotNull LoginResultType type, @Nullable String detail) {
        return new AuthResult(type, null, null, null, null, null, 0, null, detail);
    }

    /** Внутренняя ошибка. Игроку показывается нейтральное сообщение, подробность — в лог. */
    public static @NotNull AuthResult error(@Nullable String detail) {
        return new AuthResult(LoginResultType.ERROR, null, null, null, null, null, 0, null, detail);
    }

    public boolean isSuccess() {
        return type.isSuccess();
    }

    /** Ждёт ли система следующего шага от игрока (код, капча, кнопка в мессенджере). */
    public boolean isPending() {
        return type == LoginResultType.TWO_FACTOR_REQUIRED || type == LoginResultType.CAPTCHA_REQUIRED;
    }
}
