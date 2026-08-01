package net.kofnetwork.auth.paper.captcha;

import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.CaptchaType;
import net.kofnetwork.auth.api.service.CaptchaService;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Текстовая CAPTCHA: код показывается в чате, игрок вводит его командой.
 *
 * <p>Самый слабый из вариантов — код уходит клиенту обычной строкой, и бот читает
 * его прямо из пакета чата. Оставлен как запасной для клиентов, у которых не
 * открывается GUI (моды, экзотические лаунчеры), и как вариант по умолчанию, когда
 * рендерер сетки не зарегистрирован.
 *
 * <p>Пробелы между символами вставляются намеренно: они мешают простейшему боту,
 * который ищет в чате подстроку фиксированной длины, и не мешают человеку.
 */
public final class ChatCaptchaRenderer implements CaptchaService.CaptchaRenderer {

    @Override
    public @NotNull List<CaptchaType> supportedTypes() {
        return List.of(CaptchaType.TEXT_INPUT);
    }

    @Override
    public @NotNull CaptchaService.RenderedCaptcha render(@NotNull CaptchaChallenge challenge,
                                                          @NotNull String answer) {
        StringBuilder spaced = new StringBuilder(answer.length() * 2);
        for (int i = 0; i < answer.length(); i++) {
            if (i > 0) {
                spaced.append(' ');
            }
            spaced.append(answer.charAt(i));
        }
        return new CaptchaService.RenderedCaptcha(
                "Введите код: " + spaced + "  →  /captcha <код>",
                List.of(),
                null);
    }
}
