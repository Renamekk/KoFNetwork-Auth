package net.kofnetwork.auth.core.mail;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Подстановка значений в шаблоны писем.
 *
 * <p>Шаблоны лежат файлами рядом с конфигурацией, чтобы администратор мог менять
 * оформление без пересборки. Отсутствующий файл — не ошибка: используется встроенный
 * запасной шаблон, потому что письмо о сбросе пароля важнее его вёрстки.
 *
 * <p><b>Экранирование.</b> Значения подстановок вставляются в HTML с экранированием.
 * Без него ник вида {@code <script>} превратил бы письмо о входе в вектор атаки на
 * почтовый клиент получателя.
 */
public final class MailTemplateEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailTemplateEngine.class);

    /** Результат подстановки. */
    public record Rendered(@NotNull String subject, @NotNull String body) {
    }

    private final ConfigurationService config;

    public MailTemplateEngine(@NotNull ConfigurationService config) {
        this.config = config;
    }

    /**
     * Готовит письмо по шаблону.
     *
     * @param template  идентификатор: {@code verify-email}, {@code password-reset},
     *                  {@code new-login}, {@code security-alert}
     * @param variables подстановки вида {@code {{code}}}
     */
    public @NotNull Rendered render(@NotNull String template, @NotNull Map<String, String> variables) {
        String subject = substitute(subjectFor(template), variables);
        String body = substitute(loadBody(template), variables);
        return new Rendered(subject, body);
    }

    private String subjectFor(String template) {
        return switch (template) {
            case "verify-email" -> "KoF Network — подтверждение адреса";
            case "password-reset" -> "KoF Network — восстановление пароля";
            case "new-login" -> "KoF Network — вход с нового устройства";
            case "security-alert" -> "KoF Network — оповещение безопасности";
            default -> "KoF Network";
        };
    }

    private String loadBody(String template) {
        String directory = config.getString(ConfigFile.MAIL, "templates.directory", "mail-templates");
        Path file = config.configDirectory().resolve(directory).resolve(template + ".html");
        if (Files.isRegularFile(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.warn("Не удалось прочитать шаблон {}, используется встроенный", file, e);
            }
        }
        return fallbackBody(template);
    }

    /** Встроенный шаблон: письмо должно уйти даже без файлов оформления. */
    private static String fallbackBody(String template) {
        String content = switch (template) {
            case "verify-email" -> """
                    <p>Код подтверждения адреса: <b>{{code}}</b></p>
                    <p>Код действует {{expires}}. Если вы не привязывали почту к аккаунту
                    KoF Network, просто проигнорируйте это письмо.</p>
                    """;
            case "password-reset" -> """
                    <p>Код для смены пароля: <b>{{code}}</b></p>
                    <p>Код действует {{expires}}. Если вы не запрашивали смену пароля,
                    <b>немедленно смените его</b> — кто-то знает ваш ник и пытается
                    получить доступ к аккаунту.</p>
                    """;
            case "new-login" -> """
                    <p>Выполнен вход в аккаунт <b>{{username}}</b>.</p>
                    <p>Адрес: {{ip}}<br>Расположение: {{location}}<br>Время: {{time}}</p>
                    <p>Если это были не вы — смените пароль и включите двухфакторную
                    аутентификацию.</p>
                    """;
            default -> "<p>{{message}}</p>";
        };
        return """
                <!doctype html>
                <html lang="ru"><body style="font-family:sans-serif;line-height:1.5;color:#222">
                <h2 style="color:#5555FF">KoF Network</h2>
                %s
                <hr style="border:none;border-top:1px solid #ddd">
                <p style="font-size:12px;color:#888">
                Сотрудники KoF Network никогда не спрашивают пароль. Это письмо отправлено
                автоматически, отвечать на него не нужно.
                </p>
                </body></html>
                """.formatted(content);
    }

    /** Подставляет значения, экранируя их для HTML. */
    private static String substitute(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", escapeHtml(entry.getValue()));
        }
        return result;
    }

    /**
     * Экранирует HTML.
     *
     * <p>Ник и город приходят из внешнего мира. Без экранирования ник
     * {@code <img src=x onerror=...>} исполнился бы в почтовом клиенте владельца.
     */
    static @NotNull String escapeHtml(@NotNull String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
