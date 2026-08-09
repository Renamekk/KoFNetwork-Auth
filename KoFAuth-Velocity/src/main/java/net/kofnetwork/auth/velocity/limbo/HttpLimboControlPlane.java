package net.kofnetwork.auth.velocity.limbo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Обращение к внешней службе управления Limbo по HTTP.
 *
 * <p><b>Границы полномочий заданы здесь и продублированы на той стороне.</b> Клиент
 * умеет ровно три вещи — перечислить, включить, выключить — и только для имён из
 * {@code limbo.servers}. Ни создать инстанс, ни выполнить произвольную команду он не
 * может, потому что таких запросов в протоколе нет. Это прямая замена сокету Docker,
 * доступ к которому означал бы право запустить на хосте что угодно.
 *
 * <p>Отказ службы — не авария сети: маршрутизация продолжает работать по тому, что уже
 * поднято. Поэтому неудачные запросы возвращают {@code false}, а не бросают исключение;
 * решение о том, что делать дальше, принимает {@link LimboLifecycleController}.
 */
public final class HttpLimboControlPlane implements LimboControlPlane {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI baseUri;
    private final String token;
    private final Duration timeout;
    private final Set<String> allowed;
    private final Logger logger;

    private HttpLimboControlPlane(URI baseUri, String token, Duration timeout,
                                  Set<String> allowed, Logger logger) {
        this.baseUri = baseUri;
        this.token = token;
        this.timeout = timeout;
        this.allowed = allowed;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Собирает клиента по конфигурации.
     *
     * <p>Без адреса или без токена возвращается {@link LimboControlPlane#disabled()}:
     * обращаться к службе без учётных данных бессмысленно, а делать вид, что управление
     * работает, — вредно, потому что планировщик решит, будто ёмкость можно нарастить.
     */
    public static @NotNull LimboControlPlane fromConfig(@NotNull ConfigurationService config,
                                                        @NotNull List<String> managedInstances,
                                                        @NotNull Logger logger) {
        if (!config.getBoolean(ConfigFile.VELOCITY, "limbo.lifecycle.enabled", false)) {
            logger.info("Управление жизненным циклом Limbo выключено: инстансы поднимаются извне");
            return LimboControlPlane.disabled();
        }
        String url = config.getString(ConfigFile.VELOCITY, "limbo.lifecycle.control-plane-url", "");
        String token = config.getString(ConfigFile.VELOCITY, "limbo.lifecycle.token", "");
        if (url.isBlank() || token.isBlank()) {
            logger.error("limbo.lifecycle.enabled: true, но не заданы control-plane-url или token. "
                    + "Управление жизненным циклом выключено — инстансы придётся поднимать вручную.");
            return LimboControlPlane.disabled();
        }
        Duration timeout = config.getDuration(ConfigFile.VELOCITY,
                "limbo.lifecycle.request-timeout", Duration.ofSeconds(10));

        logger.info("Управление жизненным циклом Limbo включено через {} для инстансов {}",
                url, managedInstances);
        return new HttpLimboControlPlane(URI.create(url.endsWith("/") ? url : url + "/"),
                token, timeout, Set.copyOf(managedInstances), logger);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public @NotNull CompletableFuture<List<InstanceState>> list() {
        return send(HttpRequest.newBuilder(baseUri.resolve("instances")).GET())
                .thenApply(body -> {
                    if (body == null) {
                        return List.<InstanceState>of();
                    }
                    try {
                        JsonNode root = JSON.readTree(body);
                        JsonNode instances = root.path("instances");
                        List<InstanceState> states = new ArrayList<>();
                        for (JsonNode node : instances) {
                            String name = node.path("name").asText("");
                            if (name.isBlank() || !allowed.contains(name)) {
                                // Служба вправе управлять чем-то ещё; нас это не касается,
                                // и подмешивать чужие инстансы в расчёт ёмкости нельзя.
                                continue;
                            }
                            states.add(new InstanceState(name,
                                    node.path("running").asBoolean(false),
                                    node.path("ready").asBoolean(false)));
                        }
                        return states;
                    } catch (Exception e) {
                        logger.warn("Ответ control-plane не разобран: {}", e.getMessage());
                        return List.<InstanceState>of();
                    }
                });
    }

    @Override
    public @NotNull CompletableFuture<Boolean> start(@NotNull String instance) {
        return act(instance, "start");
    }

    @Override
    public @NotNull CompletableFuture<Boolean> stop(@NotNull String instance) {
        return act(instance, "stop");
    }

    /**
     * Имя проверяется до запроса.
     *
     * <p>Перечень управляемых инстансов берётся из той же конфигурации, что и
     * маршрутизация, поэтому попросить включить посторонний сервер нельзя даже при
     * ошибке в коде выше по стеку. На стороне службы такая же проверка обязана быть
     * продублирована: доверять клиенту, стоящему на границе сети, нельзя.
     */
    private CompletableFuture<Boolean> act(String instance, String action) {
        if (!allowed.contains(instance)) {
            logger.error("Отказ: {} не входит в перечень управляемых Limbo-инстансов", instance);
            return CompletableFuture.completedFuture(false);
        }
        URI uri = baseUri.resolve("instances/" + instance + "/"
                + action.toLowerCase(Locale.ROOT));
        return send(HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.noBody()))
                .thenApply(body -> body != null);
    }

    /** @return тело ответа при успехе, {@code null} при любой неудаче */
    private CompletableFuture<String> send(HttpRequest.Builder builder) {
        HttpRequest request = builder
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(timeout)
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, failure) -> {
                    if (failure != null) {
                        logger.warn("Control-plane не ответил на {} {}: {}",
                                request.method(), request.uri(), failure.getMessage());
                        return null;
                    }
                    if (response.statusCode() / 100 != 2) {
                        logger.warn("Control-plane ответил {} на {} {}",
                                response.statusCode(), request.method(), request.uri());
                        return null;
                    }
                    return response.body();
                });
    }
}
