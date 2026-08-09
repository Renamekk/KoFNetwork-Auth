package net.kofnetwork.auth.webapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.api.service.LinkService;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.webapi.dto.ApiDtos;
import net.kofnetwork.auth.webapi.util.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Привязка игрового аккаунта к вкладке браузера.
 *
 * <p>Первая из трёх кнопок личного кабинета. Две другие — Telegram и Discord —
 * живут в {@link BindingController} и требуют уже установленной связи с игровым
 * аккаунтом, потому что выдают код <em>от имени</em> этого аккаунта. Эта кнопка
 * связь и устанавливает, поэтому единственная работает без авторизации.
 *
 * <p><b>Схема.</b> Device authorization grant:
 * <ol>
 *   <li>браузер просит код — {@code POST /api/link/server/start};</li>
 *   <li>игрок вводит код в игре — {@code /link <код>};</li>
 *   <li>браузер опрашивает состояние — {@code POST /api/link/server/poll} —
 *       и получает токены доступа.</li>
 * </ol>
 *
 * <p><b>Почему подтверждает игра, а не сайт.</b> Владение игровым аккаунтом
 * доказуемо только внутри игры. Если бы связь устанавливалась вводом ника на
 * сайте, начать привязку к чужому нику мог бы кто угодно. Здесь же посетитель
 * сайта не сообщает ничего — он лишь показывает код, а решение принимает тот,
 * кто уже прошёл пароль в игре.
 *
 * <p><b>Привязка ведётся по UUID.</b> Подтверждение несёт UUID игрока, а не ник:
 * ник в сети меняется, UUID остаётся.
 */
@RestController
@RequestMapping("/api/link")
@Tag(name = "Привязка игрового аккаунта")
public class LinkController {

    private final KoFAuthCore core;

    public LinkController(KoFAuthCore core) {
        this.core = core;
    }

    /** Тело опроса состояния. */
    public record PollBody(@NotBlank @Size(max = 128) String pollToken) {
    }

    @PostMapping("/server/start")
    @Operation(summary = "Начать привязку игрового аккаунта",
            description = "Возвращает код для ввода в игре командой /link и секрет "
                    + "для опроса состояния. Авторизация не требуется: посетитель "
                    + "сайта здесь ничего о себе не утверждает.")
    public ResponseEntity<?> start(HttpServletRequest request) {
        AuthContext context = RequestContext.of(request, core.config());

        var result = core.links().startWebLink(context).join();
        if (!result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiDtos.ErrorResponse.of(result.errorCode(),
                            "Привязка временно недоступна"));
        }

        LinkService.WebLinkRequest link = result.value();
        return ResponseEntity.ok(Map.of(
                "code", link.code(),
                "pollToken", link.pollToken(),
                "expiresInSeconds", link.ttl().toSeconds(),
                "instruction", "Зайдите в игру и введите: /link " + link.code()));
    }

    @PostMapping("/server/poll")
    @Operation(summary = "Узнать, подтвердил ли игрок код",
            description = "Пока подтверждения нет — 202 Accepted. После подтверждения "
                    + "— пара токенов доступа. Выдаётся один раз: повторный опрос "
                    + "с тем же секретом вернёт 202.")
    public ResponseEntity<?> poll(@Valid @RequestBody PollBody body,
                                  HttpServletRequest request) {

        Optional<LinkService.WebLinkConfirmation> confirmation =
                core.links().pollWebLink(body.pollToken()).join();

        if (confirmation.isEmpty()) {
            // Ожидание, истёкший срок и чужой секрет отвечают одинаково.
            // Различимый ответ превратил бы опрос в способ проверять,
            // существует ли активный запрос на привязку.
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("status", "pending"));
        }

        return ResponseEntity.ok(issueTokens(confirmation.get(), request));
    }

    /**
     * Выдаёт браузеру токены доступа от имени подтверждённого аккаунта.
     *
     * <p>Создаётся полноценная веб-сессия: она видна игроку в списке устройств
     * и завершается кнопкой «выйти со всех устройств». Привязка, которую нельзя
     * отозвать, была бы хуже пароля.
     */
    private Map<String, Object> issueTokens(LinkService.WebLinkConfirmation confirmation,
                                            HttpServletRequest request) {
        AuthContext context = RequestContext.of(request, core.config());

        var session = core.sessions()
                .create(confirmation.accountId(), SessionType.WEB, context, null)
                .join();

        var pair = core.tokens()
                .issueTokenPair(confirmation.accountId(), session.id(), context.ip())
                .join();

        return Map.of(
                "status", "confirmed",
                "username", confirmation.username(),
                "uuid", confirmation.playerUuid().toString(),
                "tokens", ApiDtos.TokenPairResponse.of(pair));
    }
}
