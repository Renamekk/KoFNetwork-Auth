package net.kofnetwork.auth.webapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.core.security.TokenGenerator;
import net.kofnetwork.auth.webapi.dto.ApiDtos;
import net.kofnetwork.auth.webapi.oauth.DiscordOAuthClient;
import net.kofnetwork.auth.webapi.security.AuthenticatedUser;
import net.kofnetwork.auth.webapi.util.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Привязка Discord через OAuth2 из личного кабинета.
 *
 * <p>Альтернатива привязке кодом: игрок не переписывает код из игры, а подтверждает
 * доступ на стороне Discord. Итог одинаков — строка в {@code discord}.
 *
 * <p><b>Как устроена защита от подмены.</b> Начало потока требует токена: аккаунт
 * берётся из него, а не из параметра запроса. В ответ выдаётся одноразовое значение
 * {@code state}, которое кладётся в Redis под свой аккаунт на десять минут. Возврат
 * от Discord токена уже не несёт — браузер приходит по обычной ссылке, — и аккаунт
 * восстанавливается ровно из {@code state}. Без этой связки достаточно было бы
 * подсунуть владельцу ссылку возврата со своим кодом, чтобы привязать свой Discord
 * к чужому аккаунту.
 *
 * <p>{@code state} гасится атомарно через {@code GETDEL}: повторный переход по той
 * же ссылке возврата ничего не привяжет.
 */
@RestController
@RequestMapping("/api/discord")
@Tag(name = "Discord OAuth2")
public class DiscordOAuthController {

    /** Префикс ключа в Redis. Отдельный от прочих, чтобы не пересечься по имени. */
    private static final String STATE_KEY = "discord-oauth-state:";

    /**
     * Срок жизни {@code state}.
     *
     * <p>Десять минут — столько занимает вход в Discord и подтверждение доступа
     * у человека, который делает это впервые. Больше значит держать открытым окно,
     * в котором украденная ссылка ещё сработает.
     */
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final KoFAuthCore core;
    private final DiscordOAuthClient oauth;

    public DiscordOAuthController(KoFAuthCore core, DiscordOAuthClient oauth) {
        this.core = core;
        this.oauth = oauth;
    }

    @PostMapping("/oauth/start")
    @Operation(summary = "Начать привязку Discord через OAuth2",
            description = "Возвращает ссылку, на которую нужно отправить браузер.")
    @SecurityRequirement(name = "bearer")
    public ResponseEntity<?> start(HttpServletRequest request) {
        AuthenticatedUser user = AccountController.require(request);
        if (user == null) {
            return AccountController.unauthorized();
        }
        if (!oauth.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    ApiDtos.ErrorResponse.of("OAUTH_DISABLED",
                            "Привязка через Discord не настроена, используйте код из игры"));
        }

        String state = TokenGenerator.randomToken(16);
        boolean stored = core.cache()
                .setIfAbsent(STATE_KEY + state, String.valueOf(user.accountId()), STATE_TTL)
                .join();

        if (!stored) {
            // setIfAbsent при отказе Redis возвращает false. Начинать поток, который
            // некому будет завершить, бессмысленно — честнее отказать сразу.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    ApiDtos.ErrorResponse.of("OAUTH_UNAVAILABLE",
                            "Не удалось начать привязку, попробуйте позже"));
        }

        return ResponseEntity.ok(Map.of(
                "url", oauth.authorizeUrl(state),
                "expiresInSeconds", STATE_TTL.toSeconds()));
    }

    /**
     * Возврат из Discord.
     *
     * <p>Отдаёт не JSON, а редирект в кабинет: сюда приходит браузер пользователя,
     * а не клиент API, и показывать ему тело ответа незачем. Итог передаётся
     * параметром, который кабинет превращает в сообщение.
     */
    @GetMapping("/callback")
    @Operation(summary = "Возврат из Discord",
            description = "Вызывается браузером. Перенаправляет в личный кабинет с итогом привязки.")
    public ResponseEntity<Void> callback(@RequestParam(value = "code", required = false) String code,
                                         @RequestParam(value = "state", required = false) String state,
                                         @RequestParam(value = "error", required = false) String error,
                                         HttpServletRequest request) {
        if (error != null) {
            // Пользователь нажал «Отмена» на экране согласия — это не сбой.
            return redirect("denied");
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            return redirect("bad_request");
        }

        Optional<String> owner = core.cache().getAndDelete(STATE_KEY + state).join();
        if (owner.isEmpty()) {
            // state не найден: истёк, уже использован либо подделан. Различать эти
            // случаи в ответе незачем — реакция у пользователя одна.
            return redirect("expired");
        }

        long accountId;
        try {
            accountId = Long.parseLong(owner.get());
        } catch (NumberFormatException e) {
            return redirect("expired");
        }

        Optional<DiscordOAuthClient.DiscordProfile> profile = oauth.exchange(code);
        if (profile.isEmpty()) {
            return redirect("exchange_failed");
        }

        var result = core.links().linkVerifiedDiscord(accountId,
                profile.get().discordId(),
                profile.get().username(),
                RequestContext.of(request, core.config())).join();

        return redirect(result.isSuccess() ? "linked" : result.errorCode().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Перенаправление в кабинет.
     *
     * <p>Адрес фиксирован и не берётся из запроса: параметр {@code redirect}, каким
     * бы удобным он ни казался, превращает этот эндпоинт в открытый редиректор.
     */
    private static ResponseEntity<Void> redirect(String outcome) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/?discord=" + outcome))
                .build();
    }

    /** Ключ хранилища состояния — для тестов. */
    static String stateKey(String state) {
        return STATE_KEY + state;
    }
}
