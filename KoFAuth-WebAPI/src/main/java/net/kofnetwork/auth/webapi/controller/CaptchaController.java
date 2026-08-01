package net.kofnetwork.auth.webapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.service.CaptchaService;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.core.service.impl.CaptchaServiceImpl;
import net.kofnetwork.auth.webapi.captcha.WebCaptchaRenderer;
import net.kofnetwork.auth.webapi.dto.ApiDtos;
import net.kofnetwork.auth.webapi.security.AuthenticatedUser;
import net.kofnetwork.auth.webapi.util.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Выдача и проверка CAPTCHA в браузере.
 *
 * <p>Эндпоинты анонимны намеренно: задача выдаётся до входа, и требовать токен
 * для её получения означало бы не иметь CAPTCHA на форме входа вовсе.
 * Ограничение скорости на них общее с остальным API — {@code RateLimitFilter}.
 *
 * <p>Ответ на задачу не возвращается никогда и ни в каком виде: наружу уходят
 * только формулировка, варианты и картинка. Правильный ответ живёт в памяти
 * процесса, который выдал задачу, а в базе — его SHA-256.
 */
@RestController
@RequestMapping("/api/captcha")
@Tag(name = "CAPTCHA")
public class CaptchaController {

    private final KoFAuthCore core;

    public CaptchaController(KoFAuthCore core) {
        this.core = core;
        // Рендерер регистрируется здесь, а не в конфигурации приложения: без него
        // CaptchaService не умеет разложить ни одну задачу, и контроллер, который
        // его не зарегистрировал, был бы неработоспособен по построению.
        core.captcha().registerRenderer(new WebCaptchaRenderer(
                core.config().getInt(ConfigFile.CAPTCHA, "web.buttons", 6)));
    }

    @PostMapping
    @Operation(summary = "Выдать задачу",
            description = "Возвращает идентификатор задачи, формулировку и — в зависимости "
                    + "от типа — варианты ответа либо PNG в base64. Правильный ответ не возвращается.")
    public ResponseEntity<?> issue(HttpServletRequest request) {
        AuthContext context = RequestContext.of(request, core.config());
        AuthenticatedUser user =
                (AuthenticatedUser) request.getAttribute(AuthenticatedUser.ATTRIBUTE);

        // Анонимный посетитель не имеет UUID игрока. Ключ нужен сервису только для
        // поиска незавершённой задачи, а в вебе её держит клиент по challengeId —
        // поэтому случайного значения достаточно.
        CaptchaChallenge challenge = core.captcha()
                .issue(user == null ? null : user.accountId(), UUID.randomUUID(), null, context)
                .join();

        CaptchaService.RenderedCaptcha rendered =
                ((CaptchaServiceImpl) core.captcha()).render(challenge);

        if (rendered == null) {
            // Рендерер зарегистрирован в конструкторе, поэтому сюда можно попасть
            // только если задача уже завершена — а такую мы только что не выдавали.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    ApiDtos.ErrorResponse.of("CAPTCHA_UNAVAILABLE", "Не удалось выдать задачу"));
        }

        return ResponseEntity.ok(new ApiDtos.CaptchaChallengeResponse(
                challenge.challengeId(),
                challenge.type().name(),
                rendered.prompt(),
                rendered.options(),
                rendered.imageBase64(),
                challenge.maxAttempts() - challenge.attempts(),
                challenge.expiresAt().toString()));
    }

    @PostMapping("/verify")
    @Operation(summary = "Проверить ответ")
    public ResponseEntity<?> verify(@Valid @RequestBody ApiDtos.CaptchaBody body) {
        CaptchaService.CaptchaVerdict verdict =
                core.captcha().verify(body.challengeId(), body.answer()).join();

        if (verdict.passed()) {
            return ResponseEntity.ok(new ApiDtos.CaptchaVerdictResponse(
                    true, false, verdict.remainingAttempts()));
        }

        // Исчерпанные попытки и ненайденная задача отвечают одинаково — 410:
        // в обоих случаях клиенту остаётся запросить новую, а различие подсказывало
        // бы перебирающему, угадал ли он существующий идентификатор.
        if (verdict.exhausted()) {
            return ResponseEntity.status(HttpStatus.GONE).body(new ApiDtos.CaptchaVerdictResponse(
                    false, true, 0));
        }

        return ResponseEntity.badRequest().body(new ApiDtos.CaptchaVerdictResponse(
                false, false, verdict.remainingAttempts()));
    }
}
