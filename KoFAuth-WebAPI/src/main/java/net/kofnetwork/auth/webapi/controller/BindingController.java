package net.kofnetwork.auth.webapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.webapi.dto.ApiDtos;
import net.kofnetwork.auth.webapi.security.AuthenticatedUser;
import net.kofnetwork.auth.webapi.util.RequestContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Привязки и второй фактор: почта, Telegram, Discord, TOTP.
 *
 * <p>Коды привязки мессенджеров выдаются здесь, а вводятся в самом мессенджере.
 * Обратный порядок сломал бы модель доверия: если код выдаёт бот по нику, любой
 * знающий чужой ник привяжет к себе чужой аккаунт.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Привязки и 2FA")
@SecurityRequirement(name = "bearer")
public class BindingController {

    private final KoFAuthCore core;

    public BindingController(KoFAuthCore core) {
        this.core = core;
    }

    // ------------------------------------------------------------------ почта

    @PostMapping("/email/link")
    @Operation(summary = "Привязать почту и получить код подтверждения")
    public ResponseEntity<?> linkEmail(@Valid @RequestBody ApiDtos.EmailBody body,
                                       HttpServletRequest request) {
        return act(request, (user, context) ->
                core.email().linkEmail(user.accountId(), body.email(), context).join());
    }

    @PostMapping("/email/verify")
    @Operation(summary = "Подтвердить почту кодом из письма")
    public ResponseEntity<?> verifyEmail(@Valid @RequestBody ApiDtos.CodeBody body,
                                          HttpServletRequest request) {
        return act(request, (user, context) ->
                core.email().verifyEmail(body.code(), context).join());
    }

    @PostMapping("/email/resend")
    @Operation(summary = "Выслать код подтверждения заново")
    public ResponseEntity<?> resendEmail(HttpServletRequest request) {
        return act(request, (user, context) ->
                core.email().resendVerification(user.accountId(), context).join());
    }

    @DeleteMapping("/email")
    @Operation(summary = "Отвязать почту")
    public ResponseEntity<?> unlinkEmail(HttpServletRequest request) {
        return act(request, (user, context) ->
                core.email().unlinkEmail(user.accountId(), context).join());
    }

    // ------------------------------------------------------------------ мессенджеры

    @PostMapping("/telegram/link")
    @Operation(summary = "Получить код привязки Telegram",
            description = "Код вводится боту командой /link <код>.")
    public ResponseEntity<?> telegramCode(HttpServletRequest request) {
        AuthenticatedUser user = AccountController.require(request);
        if (user == null) {
            return AccountController.unauthorized();
        }
        var result = core.links().createTelegramLinkCode(user.accountId(),
                RequestContext.of(request, core.config())).join();
        return result.isSuccess()
                ? ResponseEntity.ok(Map.of("code", result.value().code(),
                        "expiresInSeconds", result.value().ttl().toSeconds()))
                : ResponseEntity.badRequest().body(
                        ApiDtos.ErrorResponse.of(result.errorCode(), "Не удалось выдать код"));
    }

    @DeleteMapping("/telegram")
    @Operation(summary = "Отвязать Telegram")
    public ResponseEntity<?> unlinkTelegram(HttpServletRequest request) {
        return act(request, (user, context) ->
                core.links().unlinkTelegram(user.accountId(), context).join());
    }

    @PostMapping("/discord/link")
    @Operation(summary = "Получить код привязки Discord")
    public ResponseEntity<?> discordCode(HttpServletRequest request) {
        AuthenticatedUser user = AccountController.require(request);
        if (user == null) {
            return AccountController.unauthorized();
        }
        var result = core.links().createDiscordLinkCode(user.accountId(),
                RequestContext.of(request, core.config())).join();
        return result.isSuccess()
                ? ResponseEntity.ok(Map.of("code", result.value().code(),
                        "expiresInSeconds", result.value().ttl().toSeconds()))
                : ResponseEntity.badRequest().body(
                        ApiDtos.ErrorResponse.of(result.errorCode(), "Не удалось выдать код"));
    }

    @DeleteMapping("/discord")
    @Operation(summary = "Отвязать Discord")
    public ResponseEntity<?> unlinkDiscord(HttpServletRequest request) {
        return act(request, (user, context) ->
                core.links().unlinkDiscord(user.accountId(), context).join());
    }

    // ------------------------------------------------------------------ TOTP

    @PostMapping("/totp/setup")
    @Operation(summary = "Начать подключение TOTP",
            description = "Возвращает секрет, QR-код и резервные коды. Единственный "
                    + "момент, когда они существуют в открытом виде.")
    public ResponseEntity<?> totpSetup(HttpServletRequest request) {
        AuthenticatedUser user = AccountController.require(request);
        if (user == null) {
            return AccountController.unauthorized();
        }
        var result = core.totp().beginSetup(user.accountId(), user.username(),
                RequestContext.of(request, core.config())).join();
        return result.isSuccess()
                ? ResponseEntity.ok(result.value())
                : ResponseEntity.badRequest().body(ApiDtos.ErrorResponse.of(
                        result.errorCode(), "Не удалось начать подключение"));
    }

    @PostMapping("/totp/confirm")
    @Operation(summary = "Подтвердить подключение TOTP кодом из приложения")
    public ResponseEntity<?> totpConfirm(@Valid @RequestBody ApiDtos.CodeBody body,
                                          HttpServletRequest request) {
        return act(request, (user, context) ->
                core.totp().confirmSetup(user.accountId(), body.code(), context).join());
    }

    @PostMapping("/totp/disable")
    @Operation(summary = "Отключить TOTP",
            description = "Требует код подтверждения: отключение второго фактора — "
                    + "самая ценная для злоумышленника операция.")
    public ResponseEntity<?> totpDisable(@Valid @RequestBody ApiDtos.CodeBody body,
                                          HttpServletRequest request) {
        return act(request, (user, context) ->
                core.totp().disable(user.accountId(), body.code(), context).join());
    }

    @PostMapping("/totp/recovery-codes")
    @Operation(summary = "Перевыпустить резервные коды")
    public ResponseEntity<?> regenerateCodes(HttpServletRequest request) {
        AuthenticatedUser user = AccountController.require(request);
        if (user == null) {
            return AccountController.unauthorized();
        }
        var result = core.totp().regenerateRecoveryCodes(user.accountId(),
                RequestContext.of(request, core.config())).join();
        return result.isSuccess()
                ? ResponseEntity.ok(Map.of("codes", result.value()))
                : ResponseEntity.badRequest().body(ApiDtos.ErrorResponse.of(
                        result.errorCode(), "Не удалось перевыпустить коды"));
    }

    @GetMapping("/totp/status")
    @Operation(summary = "Состояние второго фактора")
    public ResponseEntity<?> totpStatus(HttpServletRequest request) {
        AuthenticatedUser user = AccountController.require(request);
        if (user == null) {
            return AccountController.unauthorized();
        }
        boolean enabled = core.totp().isEnabled(user.accountId()).join();
        int remaining = core.totp().countRemainingRecoveryCodes(user.accountId()).join();
        return ResponseEntity.ok(Map.of("enabled", enabled, "recoveryCodesLeft", remaining));
    }

    // ------------------------------------------------------------------ общее

    /** Выполняет операцию, возвращающую {@code OperationResult<Void>}. */
    private ResponseEntity<?> act(HttpServletRequest request, Action action) {
        AuthenticatedUser user = AccountController.require(request);
        if (user == null) {
            return AccountController.unauthorized();
        }
        var result = action.run(user, RequestContext.of(request, core.config()));
        return result.isSuccess()
                ? ResponseEntity.ok(ApiDtos.OkResponse.success())
                : ResponseEntity.badRequest().body(ApiDtos.ErrorResponse.of(
                        result.errorCode() == null ? "ERROR" : result.errorCode(),
                        result.errorMessage() == null ? "Не удалось выполнить операцию"
                                : "Не удалось выполнить операцию"));
    }

    @FunctionalInterface
    private interface Action {
        net.kofnetwork.auth.api.result.OperationResult<Void> run(AuthenticatedUser user,
                                                                  AuthContext context);
    }
}
