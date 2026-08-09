package net.kofnetwork.auth.webapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.LoginRequest;
import net.kofnetwork.auth.api.dto.RegistrationRequest;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.api.result.AuthResult;
import net.kofnetwork.auth.api.result.RegistrationResult;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.webapi.dto.ApiDtos;
import net.kofnetwork.auth.webapi.security.AuthenticatedUser;
import net.kofnetwork.auth.webapi.util.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Вход, регистрация и восстановление доступа.
 *
 * <p>Единственная группа эндпоинтов, доступная без токена, — и потому самая
 * чувствительная к перечислению аккаунтов и перебору. Ограничение скорости
 * применяется внутри Core, здесь только трансляция исходов в коды HTTP.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Аутентификация")
public class AuthController {

    private final KoFAuthCore core;

    public AuthController(KoFAuthCore core) {
        this.core = core;
    }

    @PostMapping("/login")
    @Operation(summary = "Вход в личный кабинет")
    public ResponseEntity<?> login(@Valid @RequestBody ApiDtos.LoginBody body,
                                   HttpServletRequest request) {
        AuthContext context = RequestContext.of(request, core.config());
        LoginRequest loginRequest = new LoginRequest(body.username(), body.password(),
                null, body.twoFactorCode(), context);

        AuthResult result = core.authentication().login(loginRequest).join();

        return switch (result.type()) {
            case SUCCESS -> ResponseEntity.ok(ApiDtos.TokenPairResponse.of(
                    core.tokens().issueTokenPair(result.account().id(),
                            result.session().id(), context.ip()).join()));

            // Отдаётся метка попытки, а не токен. Токен был предъявительским: кто
            // его прочитал, тот и входил. Метка ничего не открывает — решение
            // принимает владелец кнопки в мессенджере, и сверяется он по своему
            // идентификатору, а не по знанию строки.
            case TWO_FACTOR_REQUIRED -> ResponseEntity.ok(new ApiDtos.TwoFactorRequiredResponse(
                    "two_factor_required",
                    String.valueOf(result.requiredTwoFactor()),
                    result.attemptId(),
                    result.approvalProof()));

            // Несуществующий аккаунт и неверный пароль отвечают одинаково:
            // различие превратило бы форму входа в проверку существования ников.
            case BAD_PASSWORD, UNKNOWN_ACCOUNT -> status(HttpStatus.UNAUTHORIZED,
                    "BAD_CREDENTIALS", "Неверный логин или пароль");

            case TEMPORARILY_LOCKED, RATE_LIMITED -> status(HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED", "Слишком много попыток, попробуйте позже");

            case ACCOUNT_LOCKED -> status(HttpStatus.FORBIDDEN,
                    "ACCOUNT_LOCKED", "Аккаунт заблокирован администрацией");

            case ACCOUNT_BANNED -> status(HttpStatus.FORBIDDEN,
                    "ACCOUNT_BANNED", "Аккаунт заблокирован");

            case CAPTCHA_REQUIRED -> status(HttpStatus.FORBIDDEN,
                    "CAPTCHA_REQUIRED", "Требуется подтверждение");

            case TWO_FACTOR_FAILED -> status(HttpStatus.UNAUTHORIZED,
                    "TWO_FACTOR_FAILED", "Неверный код подтверждения");

            case BOT_DETECTED, PROXY_DETECTED -> status(HttpStatus.FORBIDDEN,
                    "REJECTED", "Подключение отклонено");

            default -> status(HttpStatus.INTERNAL_SERVER_ERROR,
                    "ERROR", "Внутренняя ошибка");
        };
    }

    @PostMapping("/register")
    @Operation(summary = "Регистрация аккаунта")
    public ResponseEntity<?> register(@Valid @RequestBody ApiDtos.RegisterBody body,
                                      HttpServletRequest request) {
        AuthContext context = RequestContext.of(request, core.config());
        RegistrationRequest registrationRequest = new RegistrationRequest(
                body.username(), body.password(), body.passwordConfirmation(),
                null, body.email(), context);

        RegistrationResult result = core.registration().register(registrationRequest).join();

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiDtos.TokenPairResponse.of(
                    core.tokens().issueTokenPair(result.account().id(),
                            result.session().id(), context.ip()).join()));
        }

        return switch (result.type()) {
            case USERNAME_TAKEN -> status(HttpStatus.CONFLICT,
                    "USERNAME_TAKEN", "Этот ник уже занят");
            case INVALID_USERNAME -> status(HttpStatus.BAD_REQUEST,
                    "INVALID_USERNAME", "Недопустимый ник");
            case PASSWORDS_DO_NOT_MATCH -> status(HttpStatus.BAD_REQUEST,
                    "PASSWORDS_DO_NOT_MATCH", "Пароли не совпадают");
            case PASSWORD_TOO_WEAK -> ResponseEntity.badRequest().body(
                    ApiDtos.ErrorResponse.of("PASSWORD_TOO_WEAK",
                            "Пароль не соответствует требованиям: "
                                    + String.join(", ", result.passwordIssues())));
            case REGISTRATION_DISABLED -> status(HttpStatus.FORBIDDEN,
                    "REGISTRATION_DISABLED", "Регистрация временно закрыта");
            case IP_LIMIT_REACHED -> status(HttpStatus.FORBIDDEN,
                    "IP_LIMIT_REACHED", "Слишком много аккаунтов с вашего адреса");
            case RATE_LIMITED -> status(HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED", "Слишком много попыток");
            default -> status(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR", "Внутренняя ошибка");
        };
    }

    @PostMapping("/refresh")
    @Operation(summary = "Обновление пары токенов")
    public ResponseEntity<?> refresh(@Valid @RequestBody ApiDtos.RefreshBody body,
                                     HttpServletRequest request) {
        var result = core.tokens().refresh(body.refreshToken(),
                RequestContext.clientIp(request, core.config())).join();

        if (result.isFailure()) {
            // Повторное использование refresh отзывает всю цепочку внутри Core.
            // Клиенту в любом случае остаётся только войти заново.
            return status(HttpStatus.UNAUTHORIZED, "REFRESH_INVALID",
                    "Токен обновления недействителен, войдите заново");
        }
        return ResponseEntity.ok(ApiDtos.TokenPairResponse.of(result.value()));
    }

    /**
     * Состояние Telegram/Discord-подтверждения для исходной вкладки.
     *
     * <p>Идентификатор попытки не находится в URL, а proof не живёт в localStorage:
     * обновление страницы намеренно требует начать вход заново, чтобы XSS/история
     * браузера не превращались в способ забрать уже одобренную сессию.
     */
    @PostMapping("/approval/status")
    @Operation(summary = "Проверить ожидание подтверждения входа")
    public ResponseEntity<?> approvalStatus(@Valid @RequestBody ApiDtos.ApprovalPollBody body) {
        var status = core.approvals().webStatus(body.attemptId(), body.browserProof()).join();
        return ResponseEntity.ok(new ApiDtos.ApprovalStatusResponse(
                status.status().name().toLowerCase(java.util.Locale.ROOT), status.ready()));
    }

    /**
     * Единственный путь, на котором браузер получает токены после кнопки в Telegram.
     * Сам Telegram видит лишь ID задачи, а это POST-тело содержит tab-local proof;
     * атомарное погашение в Core не даёт повтору или второй вкладке получить пару ещё раз.
     */
    @PostMapping("/approval/exchange")
    @Operation(summary = "Одноразово обменять одобренную WEB-попытку на сессию")
    public ResponseEntity<?> approvalExchange(@Valid @RequestBody ApiDtos.ApprovalPollBody body,
                                              HttpServletRequest request) {
        var exchange = core.approvals().exchangeWeb(body.attemptId(), body.browserProof()).join();
        if (!exchange.consumed() || exchange.accountId() == null || exchange.sessionId() == null) {
            return status(HttpStatus.CONFLICT, "APPROVAL_NOT_EXCHANGEABLE",
                    switch (exchange.status()) {
                        case DENIED -> "Вход отклонён в мессенджере";
                        case EXPIRED -> "Подтверждение истекло или открыто в другой вкладке";
                        default -> "Подтверждение ещё не завершено";
                    });
        }
        return ResponseEntity.ok(ApiDtos.TokenPairResponse.of(core.tokens().issueTokenPair(
                exchange.accountId(), exchange.sessionId(),
                RequestContext.clientIp(request, core.config())).join()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Выход из текущей сессии")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) request.getAttribute(AuthenticatedUser.ATTRIBUTE);
        if (user == null) {
            return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Требуется авторизация");
        }
        core.sessions().revoke(user.sessionPublicId(), "LOGOUT").join();
        return ResponseEntity.ok(ApiDtos.OkResponse.success());
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Запрос восстановления пароля",
            description = "Всегда отвечает успехом: различимый ответ раскрывал бы, "
                    + "существует ли ник и есть ли у него подтверждённая почта.")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ApiDtos.ForgotPasswordBody body,
                                            HttpServletRequest request) {
        core.authentication()
                .requestPasswordReset(body.username(), RequestContext.of(request, core.config()))
                .join();
        return ResponseEntity.ok(ApiDtos.OkResponse.success());
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Завершение восстановления пароля")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ApiDtos.ResetPasswordBody body,
                                           HttpServletRequest request) {
        var result = core.authentication().completePasswordReset(body.code(), body.newPassword(),
                RequestContext.of(request, core.config())).join();

        return result.isSuccess()
                ? ResponseEntity.ok(ApiDtos.OkResponse.success())
                : status(HttpStatus.BAD_REQUEST, result.errorCode(),
                        describeReset(result.errorCode()));
    }

    private static String describeReset(String code) {
        return switch (code == null ? "" : code) {
            case "TOKEN_INVALID" -> "Код недействителен или истёк";
            case "PASSWORD_TOO_WEAK" -> "Пароль не соответствует требованиям";
            case "ACCOUNT_NOT_FOUND" -> "Аккаунт не найден";
            default -> "Не удалось сбросить пароль";
        };
    }

    private static ResponseEntity<ApiDtos.ErrorResponse> status(HttpStatus status,
                                                                String code,
                                                                String message) {
        return ResponseEntity.status(status).body(ApiDtos.ErrorResponse.of(code, message));
    }
}
