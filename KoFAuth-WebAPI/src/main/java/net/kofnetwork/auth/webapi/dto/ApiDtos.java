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

    @Schema(description = "Требуется второй фактор")
    public record TwoFactorRequiredResponse(
            String status,
            @Schema(example = "TOTP") String method,
            @Schema(description = "Токен подтверждения для Telegram и Discord") String approvalToken) {
    }
}
