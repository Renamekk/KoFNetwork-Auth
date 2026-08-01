package net.kofnetwork.auth.webapi.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.core.KoFAuthCore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Обмен кода авторизации Discord на профиль пользователя.
 *
 * <p>Живёт в веб-модуле, а не в Core: Core вызывают прокси и боты, которым исходящий
 * HTTP не нужен вовсе, и тянуть в них клиент и знание эндпоинтов Discord значит
 * платить зависимостью за код, который там никогда не выполнится.
 *
 * <p>Используется {@link HttpClient} из JDK, а не сторонний клиент: нужны ровно два
 * запроса без потоковой передачи и без повторов.
 */
@Component
public class DiscordOAuthClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordOAuthClient.class);

    private static final String AUTHORIZE_URL = "https://discord.com/oauth2/authorize";
    private static final String TOKEN_URL = "https://discord.com/api/v10/oauth2/token";
    private static final String USER_URL = "https://discord.com/api/v10/users/@me";

    /**
     * Тайм-аут на запрос.
     *
     * <p>Обмен происходит внутри HTTP-запроса от браузера: без ограничения
     * недоступность Discord держала бы поток обработчика до упора.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ConfigurationService config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            // Discord отвечает на обмен кода редиректом только при ошибке настройки;
            // следовать за ним значит отправить client_secret на чужой адрес.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Настройки берутся из Core, а не отдельным бином.
     *
     * <p>Так клиент видит перезагруженную конфигурацию: {@code /auth reload} меняет
     * содержимое того же {@link ConfigurationService}, а скопированные в поля
     * значения остались бы прежними до перезапуска процесса.
     */
    public DiscordOAuthClient(KoFAuthCore core) {
        this.config = core.config();
    }

    /** Включён ли OAuth2 и заданы ли обе половины учётных данных приложения. */
    public boolean isConfigured() {
        return config.getBoolean(ConfigFile.DISCORD, "oauth.enabled", false)
                && !clientId().isBlank()
                && !clientSecret().isBlank();
    }

    /**
     * Ссылка, на которую отправляется браузер.
     *
     * @param state одноразовое значение, связывающее возврат с начавшим его аккаунтом
     */
    public @NotNull String authorizeUrl(@NotNull String state) {
        return AUTHORIZE_URL
                + "?client_id=" + encode(clientId())
                + "&redirect_uri=" + encode(redirectUri())
                + "&response_type=code"
                + "&scope=" + encode(String.join(" ", scopes()))
                + "&state=" + encode(state)
                // Discord иначе пропускает экран согласия для уже авторизовавшего
                // приложение пользователя, и привязка «молча» проходит по клику
                // на подсунутую ссылку.
                + "&prompt=consent";
    }

    /**
     * Меняет код на профиль.
     *
     * @return профиль либо пустое значение, если Discord отказал
     */
    public @NotNull Optional<DiscordProfile> exchange(@NotNull String authorizationCode) {
        try {
            String accessToken = requestAccessToken(authorizationCode);
            if (accessToken == null) {
                return Optional.empty();
            }
            return fetchProfile(accessToken);
        } catch (IOException e) {
            LOGGER.warn("Discord недоступен при обмене кода: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private @Nullable String requestAccessToken(String code) throws IOException, InterruptedException {
        String form = "client_id=" + encode(clientId())
                + "&client_secret=" + encode(clientSecret())
                + "&grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri());

        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // Тело ответа не логируем: при ошибке настройки Discord возвращает в нём
            // отправленные параметры, среди которых client_secret.
            LOGGER.warn("Discord отклонил обмен кода: HTTP {}", response.statusCode());
            return null;
        }

        JsonNode body = mapper.readTree(response.body());
        JsonNode token = body.get("access_token");
        return token == null || token.isNull() ? null : token.asText();
    }

    private Optional<DiscordProfile> fetchProfile(String accessToken)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder(URI.create(USER_URL))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            LOGGER.warn("Discord отклонил запрос профиля: HTTP {}", response.statusCode());
            return Optional.empty();
        }

        JsonNode body = mapper.readTree(response.body());
        JsonNode id = body.get("id");
        if (id == null || id.isNull()) {
            return Optional.empty();
        }
        try {
            // id приходит строкой: snowflake не помещается в double, которым JSON
            // представляет числа, и разбор его как числа теряет младшие разряды.
            long discordId = Long.parseLong(id.asText());
            JsonNode username = body.get("username");
            return Optional.of(new DiscordProfile(discordId,
                    username == null || username.isNull() ? null : username.asText()));
        } catch (NumberFormatException e) {
            LOGGER.warn("Discord вернул нечисловой идентификатор");
            return Optional.empty();
        }
    }

    /** Адрес возврата — он же обязан совпадать с указанным в настройках приложения Discord. */
    public @NotNull String redirectUri() {
        return config.getString(ConfigFile.DISCORD, "oauth.redirect-uri", "");
    }

    private String clientId() {
        return config.getString(ConfigFile.DISCORD, "oauth.client-id", "");
    }

    private String clientSecret() {
        return config.getString(ConfigFile.DISCORD, "oauth.client-secret", "");
    }

    private List<String> scopes() {
        List<String> configured = config.getStringList(ConfigFile.DISCORD, "oauth.scopes");
        return configured.isEmpty() ? List.of("identify") : configured;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Подтверждённая Discord личность. */
    public record DiscordProfile(long discordId, @Nullable String username) {
    }
}
