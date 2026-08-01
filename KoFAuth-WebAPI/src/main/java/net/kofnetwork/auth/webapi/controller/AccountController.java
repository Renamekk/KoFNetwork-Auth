package net.kofnetwork.auth.webapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.kofnetwork.auth.api.dto.AccountProfileDto;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.DeviceDto;
import net.kofnetwork.auth.api.dto.LoginHistoryDto;
import net.kofnetwork.auth.api.dto.PasswordChangeRequest;
import net.kofnetwork.auth.api.dto.SecurityLogDto;
import net.kofnetwork.auth.api.dto.SessionDto;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.webapi.dto.ApiDtos;
import net.kofnetwork.auth.webapi.security.AuthenticatedUser;
import net.kofnetwork.auth.webapi.util.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;

/**
 * Личный кабинет: профиль, сессии, устройства, история, привязки, второй фактор.
 *
 * <p>Все эндпоинты требуют токена. Идентификатор аккаунта берётся **только** из
 * токена — параметра «чей аккаунт» в API нет вовсе, поэтому обратиться к чужому
 * аккаунту невозможно даже при ошибке в клиенте.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Личный кабинет")
@SecurityRequirement(name = "bearer")
public class AccountController {

    private final KoFAuthCore core;

    public AccountController(KoFAuthCore core) {
        this.core = core;
    }

    // ------------------------------------------------------------------ профиль

    @GetMapping("/profile")
    @Operation(summary = "Профиль аккаунта")
    public ResponseEntity<?> profile(HttpServletRequest request) {
        return authenticated(request, user -> core.authentication()
                .findAccount(user.username())
                .thenCompose(found -> found
                        .map(this::buildProfile)
                        .orElseGet(() -> CompletableFuture.completedFuture(null))));
    }

    /** Собирает профиль из аккаунта и агрегатов. */
    private CompletableFuture<AccountProfileDto> buildProfile(Account account) {
        long id = account.id();
        return core.email().findPrimary(id).thenCompose(email ->
                core.links().findTelegram(id).thenCompose(telegram ->
                        core.links().findDiscord(id).thenCompose(discord ->
                                core.totp().isEnabled(id).thenCompose(totpEnabled ->
                                        core.sessions().listSessions(id, null).thenCompose(sessions ->
                                                core.adminOperations().listDevices(id).thenCompose(devices ->
                                                        core.adminOperations().listRoles(id)
                                                                .thenApply(roles -> AccountProfileDto.from(
                                                                        account,
                                                                        email.isPresent(),
                                                                        email.map(e -> e.verified()).orElse(false),
                                                                        telegram.isPresent(),
                                                                        discord.isPresent(),
                                                                        totpEnabled,
                                                                        sessions.size(),
                                                                        devices.size(),
                                                                        roles.stream()
                                                                                .map(r -> r.name())
                                                                                .toList()))))))));
    }

    @PostMapping("/profile/password")
    @Operation(summary = "Смена пароля")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ApiDtos.ChangePasswordBody body,
                                            HttpServletRequest request) {
        AuthenticatedUser user = require(request);
        if (user == null) {
            return unauthorized();
        }
        AuthContext context = RequestContext.of(request, core.config());
        var result = core.authentication().changePassword(user.accountId(),
                new PasswordChangeRequest(body.currentPassword(), body.newPassword(),
                        body.newPasswordConfirmation(),
                        body.revokeOtherSessions() == null || body.revokeOtherSessions(),
                        null, context)).join();

        return result.isSuccess()
                ? ResponseEntity.ok(ApiDtos.OkResponse.success())
                : ResponseEntity.badRequest().body(ApiDtos.ErrorResponse.of(
                        result.errorCode(), describePassword(result.errorCode())));
    }

    private static String describePassword(String code) {
        return switch (code == null ? "" : code) {
            case "BAD_PASSWORD" -> "Текущий пароль неверен";
            case "PASSWORD_TOO_WEAK" -> "Новый пароль не соответствует требованиям";
            case "PASSWORDS_DO_NOT_MATCH" -> "Новые пароли не совпадают";
            case "PASSWORD_UNCHANGED" -> "Новый пароль совпадает с текущим";
            default -> "Не удалось сменить пароль";
        };
    }

    // ------------------------------------------------------------------ сессии

    @GetMapping("/sessions")
    @Operation(summary = "Активные сессии")
    public ResponseEntity<?> sessions(HttpServletRequest request) {
        AuthenticatedUser user = require(request);
        if (user == null) {
            return unauthorized();
        }
        List<SessionDto> sessions = core.sessions()
                .listSessions(user.accountId(), user.sessionPublicId()).join();
        return ResponseEntity.ok(sessions);
    }

    @DeleteMapping("/sessions/{publicId}")
    @Operation(summary = "Завершить конкретную сессию")
    public ResponseEntity<?> revokeSession(@PathVariable String publicId,
                                           HttpServletRequest request) {
        AuthenticatedUser user = require(request);
        if (user == null) {
            return unauthorized();
        }
        // Проверяем принадлежность: без неё зная чужой publicId можно было бы
        // разлогинить любого игрока сети.
        List<SessionDto> own = core.sessions().listSessions(user.accountId(), null).join();
        boolean belongs = own.stream().anyMatch(session -> session.publicId().equals(publicId));
        if (!belongs) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiDtos.ErrorResponse.of("SESSION_NOT_FOUND", "Сессия не найдена"));
        }
        core.sessions().revoke(publicId, Session.REASON_LOGOUT).join();
        return ResponseEntity.ok(ApiDtos.OkResponse.success());
    }

    @PostMapping("/sessions/revoke-all")
    @Operation(summary = "Выйти со всех устройств, кроме текущего")
    public ResponseEntity<?> revokeAll(HttpServletRequest request) {
        AuthenticatedUser user = require(request);
        if (user == null) {
            return unauthorized();
        }
        int revoked = core.sessions()
                .revokeAll(user.accountId(), user.sessionPublicId(), Session.REASON_LOGOUT_ALL)
                .join();
        core.tokens().revokeRefreshTokensOf(user.accountId()).join();
        return ResponseEntity.ok(java.util.Map.of("revoked", revoked));
    }

    // ------------------------------------------------------------------ устройства и история

    @GetMapping("/devices")
    @Operation(summary = "Известные устройства")
    public ResponseEntity<?> devices(HttpServletRequest request) {
        AuthenticatedUser user = require(request);
        if (user == null) {
            return unauthorized();
        }
        List<DeviceDto> devices = core.adminOperations().listDevices(user.accountId()).join()
                .stream()
                .map(device -> DeviceDto.from(device, false))
                .toList();
        return ResponseEntity.ok(devices);
    }

    @GetMapping("/history")
    @Operation(summary = "История входов")
    public ResponseEntity<?> history(@RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(defaultValue = "0") int offset,
                                     HttpServletRequest request) {
        AuthenticatedUser user = require(request);
        if (user == null) {
            return unauthorized();
        }
        List<LoginHistoryDto> history = core.audit()
                .getLoginHistory(user.accountId(), clamp(limit), Math.max(0, offset)).join();
        return ResponseEntity.ok(history);
    }

    @GetMapping("/security-log")
    @Operation(summary = "Журнал безопасности")
    public ResponseEntity<?> securityLog(@RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset,
                                         HttpServletRequest request) {
        AuthenticatedUser user = require(request);
        if (user == null) {
            return unauthorized();
        }
        List<SecurityLogDto> entries = core.audit()
                .getSecurityLog(user.accountId(), clamp(limit), Math.max(0, offset)).join();
        return ResponseEntity.ok(entries);
    }

    // ------------------------------------------------------------------ вспомогательное

    /** Выполняет действие для аутентифицированного пользователя. */
    private <T> ResponseEntity<?> authenticated(HttpServletRequest request,
                                                Function<AuthenticatedUser,
                                                        CompletableFuture<T>> action) {
        AuthenticatedUser user = require(request);
        if (user == null) {
            return unauthorized();
        }
        T value = action.apply(user).join();
        return value == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiDtos.ErrorResponse.of("NOT_FOUND", "Аккаунт не найден"))
                : ResponseEntity.ok(value);
    }

    static AuthenticatedUser require(HttpServletRequest request) {
        return (AuthenticatedUser) request.getAttribute(AuthenticatedUser.ATTRIBUTE);
    }

    static ResponseEntity<ApiDtos.ErrorResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiDtos.ErrorResponse.of("UNAUTHENTICATED", "Требуется авторизация"));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }
}
