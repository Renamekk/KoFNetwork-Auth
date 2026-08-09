package net.kofnetwork.auth.webapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.BotPlatform;
import net.kofnetwork.auth.api.service.LoginApprovalService;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.core.security.TokenGenerator;
import net.kofnetwork.auth.webapi.dto.ApiDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * API для ботов Telegram и Discord.
 *
 * <p><b>Зачем отдельная поверхность.</b> Боты написаны на Python и не могут
 * пользоваться {@code KoFAuthCore} напрямую. Дать им доступ к MySQL и Redis
 * значило бы завести второй экземпляр всей логики безопасности — с собственными
 * представлениями о сроках токенов, ограничении частоты и порядке проверок,
 * которые неизбежно разойдутся с настоящими. Здесь боты остаются тонкими:
 * решения принимает Core, бот только рисует кнопки.
 *
 * <p><b>Аутентификация — общий ключ, а не JWT.</b> Бот действует не от своего
 * имени и не от имени одного игрока: он обслуживает всех сразу и опознаёт их по
 * идентификатору мессенджера. JWT здесь нечему принадлежать. Ключ задаётся
 * переменной {@code KOFAUTH_SECURITY_BOT_API_KEY} и обязан быть задан — без
 * него эндпоинты выключены целиком, а не открыты.
 *
 * <p><b>Идентификатор мессенджера — не доказательство личности.</b> Он приходит
 * от бота, которому мы доверяем ключ; сам по себе он лишь ищет привязку.
 * Привязку же создаёт только код, выданный в игре.
 */
@RestController
@RequestMapping("/api/bot")
@Tag(name = "Боты Telegram и Discord")
public class BotController {

    private final KoFAuthCore core;

    public BotController(KoFAuthCore core) {
        this.core = core;
    }

    /** Платформа мессенджера. */
    public enum Platform {
        TELEGRAM, DISCORD
    }

    public record LinkBody(@NotBlank @Size(max = 16) String platform,
                           @NotBlank @Size(max = 64) String code,
                           long externalId,
                           long chatId) {
    }

    public record IdentityBody(@NotBlank @Size(max = 16) String platform,
                               long externalId) {
    }

    /**
     * Нажатие кнопки.
     *
     * <p>Идентификатор запроса сам по себе ничего не открывает: сервер сверяет, что
     * нажал тот, кому кнопка адресована. Раньше здесь ехал предъявительский токен —
     * кто его прочитал, тот и входил.
     */
    public record ApprovalBody(@NotBlank @Size(max = 16) String platform,
                               long externalId,
                               @NotBlank @Size(max = 64) String approvalId,
                               boolean approved) {
    }

    /** Подтверждение прочитанного ботом. */
    public record AckBody(@NotBlank @Size(max = 16) String platform,
                          long cursor) {
    }

    public record SecurityBody(@NotBlank @Size(max = 16) String platform,
                               long externalId,
                               Boolean loginApproval,
                               Boolean notifications) {
    }

    // ================================================================ привязка

    @PostMapping("/link")
    @Operation(summary = "Привязать мессенджер по коду из игры")
    public ResponseEntity<?> link(@Valid @RequestBody LinkBody body, HttpServletRequest request) {
        return guarded(request, () -> {
            Platform platform = parsePlatform(body.platform());
            AuthContext context = contextOf(platform);

            OperationResult<?> result = platform == Platform.TELEGRAM
                    ? core.links().completeTelegramLink(
                            body.code(), body.externalId(), body.chatId(), context).join()
                    : core.links().completeDiscordLink(
                            body.code(), body.externalId(), context).join();

            if (!result.isSuccess()) {
                return ResponseEntity.badRequest().body(
                        ApiDtos.ErrorResponse.of(result.errorCode(), result.errorMessage()));
            }
            // Ник возвращается, чтобы бот сразу показал, к чему привязался:
            // «привязано» без имени аккаунта не даёт человеку проверить себя.
            return accountOf(platform, body.externalId())
                    .map(account -> ResponseEntity.ok(Map.of(
                            "ok", true,
                            "username", account.username(),
                            "uuid", account.uuid().toString())))
                    .orElseGet(() -> ResponseEntity.ok(Map.of("ok", true)));
        });
    }

    @DeleteMapping("/link")
    @Operation(summary = "Отвязать мессенджер")
    public ResponseEntity<?> unlink(@Valid @RequestBody IdentityBody body,
                                    HttpServletRequest request) {
        return withAccount(request, body, (platform, account) -> {
            var result = platform == Platform.TELEGRAM
                    ? core.links().unlinkTelegram(account.id(), contextOf(platform)).join()
                    : core.links().unlinkDiscord(account.id(), contextOf(platform)).join();

            return result.isSuccess()
                    ? ResponseEntity.ok(ApiDtos.OkResponse.success())
                    : ResponseEntity.badRequest().body(
                            ApiDtos.ErrorResponse.of(result.errorCode(), result.errorMessage()));
        });
    }

    // ================================================================ сведения

    @GetMapping("/account")
    @Operation(summary = "Сведения об аккаунте, привязанном к мессенджеру")
    public ResponseEntity<?> account(@RequestParam String platform,
                                     @RequestParam long externalId,
                                     HttpServletRequest request) {
        return withAccount(request, new IdentityBody(platform, externalId),
                (kind, account) -> ResponseEntity.ok(profileOf(kind, account)));
    }

    private Map<String, Object> profileOf(Platform platform, Account account) {
        Map<String, Object> profile = new HashMap<>();
        profile.put("username", account.username());
        profile.put("uuid", account.uuid().toString());
        profile.put("status", account.status().name());
        profile.put("premium", account.premium());
        profile.put("registeredAt", account.registrationDate().toString());
        profile.put("lastLoginAt", account.lastLoginAt() == null
                ? null : account.lastLoginAt().toString());
        profile.put("lastLoginIp", account.lastLoginIp() == null
                ? null : account.lastLoginIp().asMasked());
        profile.put("country", account.lastCountry());
        profile.put("city", account.lastCity());
        profile.put("twoFactor", account.twoFactorMethods().stream().map(Enum::name).toList());
        profile.put("totpEnabled", core.totp().isEnabled(account.id()).join());
        profile.put("captchaPassed", account.captchaPassed());
        profile.put("lockedUntil", account.lockedUntil() == null
                ? null : account.lockedUntil().toString());
        profile.put("temporarilyLocked", account.isTemporarilyLocked(Instant.now()));

        boolean approval = platform == Platform.TELEGRAM
                ? core.links().findTelegram(account.id()).join()
                        .map(binding -> binding.loginApprovalEnabled()).orElse(false)
                : core.links().findDiscord(account.id()).join()
                        .map(binding -> binding.loginApprovalEnabled()).orElse(false);
        boolean notifications = platform == Platform.TELEGRAM
                ? core.links().findTelegram(account.id()).join()
                        .map(binding -> binding.notificationsEnabled()).orElse(false)
                : core.links().findDiscord(account.id()).join()
                        .map(binding -> binding.notificationsEnabled()).orElse(false);

        profile.put("loginApproval", approval);
        profile.put("notifications", notifications);
        return profile;
    }

    @GetMapping("/devices")
    @Operation(summary = "Устройства игрока")
    public ResponseEntity<?> devices(@RequestParam String platform,
                                     @RequestParam long externalId,
                                     @RequestParam(defaultValue = "10") int limit,
                                     HttpServletRequest request) {
        return withAccount(request, new IdentityBody(platform, externalId), (kind, account) ->
                ResponseEntity.ok(Map.of("devices",
                        core.adminOperations().listDevices(account.id()).join().stream()
                                .limit(bounded(limit))
                                .map(device -> mapOf(
                                        "name", device.friendlyName(),
                                        "ip", device.lastSeenIp().asMasked(),
                                        "lastSeenAt", device.lastSeenAt().toString(),
                                        "trusted", device.trusted(),
                                        "blocked", device.blocked()))
                                .toList())));
    }

    @GetMapping("/history")
    @Operation(summary = "История входов")
    public ResponseEntity<?> history(@RequestParam String platform,
                                     @RequestParam long externalId,
                                     @RequestParam(defaultValue = "10") int limit,
                                     HttpServletRequest request) {
        return withAccount(request, new IdentityBody(platform, externalId), (kind, account) ->
                ResponseEntity.ok(Map.of("history",
                        core.audit().getLoginHistory(account.id(), bounded(limit), 0).join().stream()
                                .map(entry -> mapOf(
                                        "at", entry.at().toString(),
                                        "success", entry.success(),
                                        "ip", entry.ipMasked(),
                                        "country", entry.country(),
                                        "city", entry.city(),
                                        "result", entry.result() == null
                                                ? null : entry.result().name()))
                                .toList())));
    }

    @GetMapping("/sessions")
    @Operation(summary = "Активные сессии")
    public ResponseEntity<?> sessions(@RequestParam String platform,
                                      @RequestParam long externalId,
                                      HttpServletRequest request) {
        return withAccount(request, new IdentityBody(platform, externalId), (kind, account) ->
                ResponseEntity.ok(Map.of("sessions",
                        core.sessions().listSessions(account.id(), null).join().stream()
                                .map(session -> mapOf(
                                        "publicId", session.publicId(),
                                        "type", session.type().name(),
                                        "ip", session.ipMasked(),
                                        "server", session.server(),
                                        "lastSeenAt", session.lastSeenAt().toString()))
                                .toList())));
    }

    // ================================================================ действия

    @PostMapping("/approval")
    @Operation(summary = "Подтвердить или отклонить вход",
            description = "Решение принимается ровно один раз: повторное нажатие "
                    + "вернёт уже записанный исход, а не создаст вторую сессию. "
                    + "Нажатие принимается только от того, кому адресована кнопка.")
    public ResponseEntity<?> approval(@Valid @RequestBody ApprovalBody body,
                                      HttpServletRequest request) {
        return guarded(request, () -> {
            BotPlatform platform = BotPlatform.parse(body.platform())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Неизвестная платформа: " + body.platform()));

            LoginApprovalService.Decision decision = core.approvals()
                    .decide(body.approvalId(), platform, body.externalId(), body.approved())
                    .join();

            HttpStatus status = switch (decision.result()) {
                case APPLIED -> HttpStatus.OK;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                // Нажал не тот, кому кнопка адресована. Отдельный код нужен, чтобы бот
                // ответил человеку по существу, а не «что-то пошло не так».
                case FOREIGN -> HttpStatus.FORBIDDEN;
                case EXPIRED, ALREADY_DECIDED -> HttpStatus.CONFLICT;
            };

            Map<String, Object> payload = new HashMap<>();
            payload.put("ok", decision.isApplied());
            payload.put("result", decision.result().name());
            payload.put("status", decision.status().name());
            payload.put("username", decision.username());
            return ResponseEntity.status(status).body(payload);
        });
    }

    @GetMapping("/events")
    @Operation(summary = "Сообщения боту из долговечной очереди",
            description = "Заменяет подписку ботов на Redis. Курсор хранится на сервере, "
                    + "поэтому перезапуск бота ничего не теряет.")
    public ResponseEntity<?> events(@RequestParam String platform,
                                    @RequestParam(defaultValue = "100") int limit,
                                    HttpServletRequest request) {
        return guarded(request, () -> {
            BotPlatform kind = BotPlatform.parse(platform)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Неизвестная платформа: " + platform));

            long cursor = core.botOutbox().cursor(kind).join();
            var messages = core.botOutbox()
                    .readAfter(kind, cursor, bounded(limit, 500), Instant.now()).join();

            return ResponseEntity.ok(Map.of(
                    "cursor", cursor,
                    "messages", messages.stream()
                            .map(message -> mapOf(
                                    "id", message.id(),
                                    "kind", message.kind().name(),
                                    "recipientId", message.recipientId(),
                                    "chatId", message.chatId(),
                                    "createdAt", message.createdAt().toString(),
                                    "expiresAt", message.expiresAt().toString(),
                                    "payload", message.payload()))
                            .toList()));
        });
    }

    @PostMapping("/events/ack")
    @Operation(summary = "Подтвердить обработку сообщений",
            description = "Курсор двигается только вперёд: запоздавший ответ не заставит "
                    + "разослать уже обработанное заново.")
    public ResponseEntity<?> ack(@Valid @RequestBody AckBody body, HttpServletRequest request) {
        return guarded(request, () -> {
            BotPlatform kind = BotPlatform.parse(body.platform())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Неизвестная платформа: " + body.platform()));
            long cursor = core.botOutbox().acknowledge(kind, body.cursor()).join();
            return ResponseEntity.ok(Map.of("cursor", cursor));
        });
    }

    @PostMapping("/security")
    @Operation(summary = "Включить или выключить подтверждение входа и уведомления")
    public ResponseEntity<?> security(@Valid @RequestBody SecurityBody body,
                                      HttpServletRequest request) {
        return withAccount(request, new IdentityBody(body.platform(), body.externalId()),
                (platform, account) -> {
                    if (body.loginApproval() != null) {
                        var result = platform == Platform.TELEGRAM
                                ? core.links().setTelegramLoginApproval(
                                        account.id(), body.loginApproval()).join()
                                : core.links().setDiscordLoginApproval(
                                        account.id(), body.loginApproval()).join();
                        if (!result.isSuccess()) {
                            return ResponseEntity.badRequest().body(ApiDtos.ErrorResponse.of(
                                    result.errorCode(), result.errorMessage()));
                        }
                    }
                    return ResponseEntity.ok(profileOf(platform, account));
                });
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Завершить все сессии аккаунта")
    public ResponseEntity<?> logoutAll(@Valid @RequestBody IdentityBody body,
                                       HttpServletRequest request) {
        return withAccount(request, body, (platform, account) -> {
            int revoked = core.sessions().revokeAll(account.id(), null,
                    net.kofnetwork.auth.api.model.Session.REASON_LOGOUT_ALL).join();
            return ResponseEntity.ok(Map.of("ok", true, "revoked", revoked));
        });
    }

    // ================================================================ общее

    /**
     * Проверяет ключ бота и выполняет действие.
     *
     * <p>Сравнение постоянного времени: посимвольное {@code equals} по разнице во
     * времени ответа выдаёт длину совпавшего префикса, что превращает подбор
     * ключа из перебора всего пространства в перебор по одному символу.
     */
    private ResponseEntity<?> guarded(HttpServletRequest request,
                                      java.util.function.Supplier<ResponseEntity<?>> action) {
        String expected = core.config().getString(ConfigFile.SECURITY, "bot-api.key", "");
        if (expected.isBlank()) {
            // Не задан ключ — поверхность выключена. Открывать её «пока что»
            // означало бы отдать управление всеми аккаунтами любому, кто знает адрес.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    ApiDtos.ErrorResponse.of("BOT_API_DISABLED",
                            "Ключ ботов не настроен"));
        }
        String provided = request.getHeader("X-Bot-Key");
        if (provided == null || !TokenGenerator.constantTimeEquals(provided, expected)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiDtos.ErrorResponse.of("BOT_UNAUTHORIZED", "Неверный ключ"));
        }
        return action.get();
    }

    /** Находит привязанный аккаунт и выполняет действие. */
    private ResponseEntity<?> withAccount(HttpServletRequest request,
                                          IdentityBody body,
                                          java.util.function.BiFunction<Platform, Account,
                                                  ResponseEntity<?>> action) {
        return guarded(request, () -> {
            Platform platform = parsePlatform(body.platform());
            Optional<Account> account = accountOf(platform, body.externalId());
            if (account.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ApiDtos.ErrorResponse.of("NOT_LINKED", "Аккаунт не привязан"));
            }
            return action.apply(platform, account.get());
        });
    }

    private Optional<Account> accountOf(Platform platform, long externalId) {
        CompletableFuture<Optional<Long>> lookup = platform == Platform.TELEGRAM
                ? core.links().findAccountByTelegramId(externalId)
                : core.links().findAccountByDiscordId(externalId);

        return lookup.join()
                .flatMap(accountId -> core.adminOperations().findAccount(accountId).join());
    }

    private static Platform parsePlatform(String raw) {
        try {
            return Platform.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Неизвестная платформа: " + raw);
        }
    }

    private static AuthContext contextOf(Platform platform) {
        return platform == Platform.TELEGRAM ? AuthContext.telegram() : AuthContext.discord();
    }

    /** Ограничивает размер выборки: бот всё равно показывает несколько строк. */
    private static int bounded(int limit) {
        return bounded(limit, 50);
    }

    private static int bounded(int limit, int max) {
        return Math.min(max, Math.max(1, limit));
    }

    /**
     * Карта, допускающая {@code null} в значениях.
     *
     * <p>{@code Map.of} их запрещает, а поля вроде города или результата попытки
     * законно бывают неизвестны — и должны приезжать боту как {@code null},
     * а не исчезать из ответа.
     */
    private static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    /** Неизвестная платформа — ошибка запроса, а не сбой сервера. */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> onBadPlatform(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(
                ApiDtos.ErrorResponse.of("BAD_REQUEST", e.getMessage()));
    }
}
