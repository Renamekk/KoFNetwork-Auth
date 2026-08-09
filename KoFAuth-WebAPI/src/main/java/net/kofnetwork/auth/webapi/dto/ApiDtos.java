package net.kofnetwork.auth.webapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Объекты запросов и ответов REST API.
 *
 * <p>Собраны в один файл намеренно: это плоские записи по три-четыре поля, и
 * два десятка отдельных файлов по восемь строк усложнили бы навигацию, ничего
 * не добавив.
 */
public final class ApiDtos {

    private ApiDtos() {
        throw new AssertionError("Контейнер типов не подлежит созданию");
    }

    // ------------------------------------------------------------------ запросы

    @Schema(description = "Вход в личный кабинет")
    public record LoginBody(
            @NotBlank @Size(max = 16) @Schema(example = "Steve") String username,
            @NotBlank @Size(max = 72) String password,
            @Schema(description = "Код TOTP, если включён второй фактор") String twoFactorCode) {
    }

    @Schema(description = "Регистрация аккаунта")
    public record RegisterBody(
            @NotBlank @Size(min = 3, max = 16) String username,
            @NotBlank @Size(max = 72) String password,
            @NotBlank @Size(max = 72) String passwordConfirmation,
            @Schema(description = "Необязательная привязка почты") String email) {
    }

    @Schema(description = "Обновление пары токенов")
    public record RefreshBody(@NotBlank String refreshToken) {
    }

    @Schema(description = "Смена пароля")
    public record ChangePasswordBody(
            @NotBlank String currentPassword,
            @NotBlank @Size(max = 72) String newPassword,
            @NotBlank @Size(max = 72) String newPasswordConfirmation,
            @Schema(description = "Завершить остальные сессии", defaultValue = "true")
            Boolean revokeOtherSessions) {
    }

    @Schema(description = "Запрос восстановления пароля")
    public record ForgotPasswordBody(@NotBlank @Size(max = 16) String username) {
    }

    @Schema(description = "Завершение восстановления пароля")
    public record ResetPasswordBody(
            @NotBlank String code,
            @NotBlank @Size(max = 72) String newPassword) {
    }

    @Schema(description = "Привязка почты")
    public record EmailBody(@NotBlank @Size(max = 254) String email) {
    }

    @Schema(description = "Код подтверждения")
    public record CodeBody(@NotBlank @Size(max = 64) String code) {
    }

    @Schema(description = "Ответ на CAPTCHA")
    public record CaptchaBody(@NotBlank String challengeId, @NotBlank String answer) {
    }

    /** Данные остаются только в памяти исходной вкладки и передаются POST-телом. */
    public record ApprovalPollBody(
            @NotBlank @Size(min = 36, max = 36) String attemptId,
            @NotBlank @Size(min = 64, max = 64) String browserProof) {
    }

    // ------------------------------------------------------------------ ответы

    @Schema(description = "Пара токенов доступа")
    public record TokenPairResponse(
            String accessToken,
            String refreshToken,
            @Schema(description = "Срок жизни access в секундах") long expiresIn,
            @Schema(description = "Тип токена", example = "Bearer") String tokenType) {

        public static TokenPairResponse of(net.kofnetwork.auth.api.service.TokenService.TokenPair pair) {
            return new TokenPairResponse(pair.accessToken(), pair.refreshToken(),
                    pair.accessExpiresIn().toSeconds(), "Bearer");
        }
    }

    /**
     * Ошибка.
     *
     * <p>Наружу уходит только код и человекочитаемое сообщение. Технические детали
     * (стек, текст SQL-ошибки) остаются в логе сервера: по ним слишком многое видно
     * о внутреннем устройстве.
     */
    @Schema(description = "Ошибка")
    public record ErrorResponse(
            @Schema(example = "BAD_PASSWORD") String code,
            @Schema(example = "Неверный логин или пароль") String message) {

        public static ErrorResponse of(String code, String message) {
            return new ErrorResponse(code, message);
        }
    }

    /**
     * Результат операции без данных.
     *
     * <p>Фабрика названа {@code success}, а не {@code ok}: у компонента записи
     * уже есть аксессор {@code ok()}, и одноимённый статический метод — ошибка
     * компиляции, а не перегрузка.
     */
    @Schema(description = "Результат операции без данных")
    public record OkResponse(boolean ok) {

        public static OkResponse success() {
            return new OkResponse(true);
        }
    }

    /**
     * Выданная задача CAPTCHA.
     *
     * <p>Правильного ответа тут нет и быть не может: он остаётся в памяти сервиса,
     * а в базе хранится только его SHA-256.
     *
     * @param options     варианты для {@code BUTTON_CLICK}; для остальных типов пусто
     * @param imageBase64 PNG для текстовых типов; для кнопок {@code null}
     */
    @Schema(description = "Выданная задача CAPTCHA")
    public record CaptchaChallengeResponse(
            String challengeId,
            @Schema(example = "BUTTON_CLICK") String type,
            String prompt,
            java.util.List<String> options,
            @Schema(description = "PNG в base64") String imageBase64,
            int remainingAttempts,
            @Schema(description = "Срок задачи в ISO-8601") String expiresAt) {
    }

    @Schema(description = "Результат проверки CAPTCHA")
    public record CaptchaVerdictResponse(
            boolean passed,
            @Schema(description = "Попытки исчерпаны — нужна новая задача") boolean exhausted,
            int remainingAttempts) {
    }

    @Schema(description = "Требуется второй фактор")
    /**
     * @param attemptId метка попытки, ожидающей подтверждения в мессенджере.
     *                  Это не токен: ею нельзя войти. Решение принимает владелец
     *                  кнопки, и сервер сверяет его по идентификатору мессенджера,
     *                  а не по знанию этой строки
     */
    public record TwoFactorRequiredResponse(
            String status,
            @Schema(example = "TOTP") String method,
            @Schema(description = "Метка попытки для Telegram и Discord; для TOTP пусто")
            String attemptId,
            @Schema(description = "Секрет только для исходной WEB-вкладки; не URL и не токен сессии")
            String browserProof) {
    }

    public record ApprovalStatusResponse(String status, boolean ready) {
    }
}
