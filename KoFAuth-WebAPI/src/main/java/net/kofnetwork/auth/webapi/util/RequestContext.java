package net.kofnetwork.auth.webapi.util;

import jakarta.servlet.http.HttpServletRequest;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.webapi.security.TrustedProxies;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Извлечение адреса и контекста из HTTP-запроса. */
public final class RequestContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestContext.class);

    /**
     * Разобранный перечень доверенных посредников.
     *
     * <p>Кэшируется по исходной строке конфигурации: разбор CIDR на каждом запросе —
     * лишняя работа в горячем пути, а перезагрузка конфигурации меняет ключ и
     * заставляет разобрать заново.
     */
    private static final Map<String, TrustedProxies> PARSED = new ConcurrentHashMap<>();

    private RequestContext() {
        throw new AssertionError("Утилитный класс не подлежит созданию");
    }

    /**
     * Адрес клиента.
     *
     * <p><b>Заголовку верят только от доверенного посредника.</b> Раньше проверялся
     * лишь флаг {@code forwarding.trust-proxy-header}, а перечень
     * {@code forwarding.trusted-proxies} не использовался вовсе — то есть заголовок
     * принимался от кого угодно, кто дотянулся до сервиса напрямую. Этим обходились
     * сразу три механизма: ограничение скорости считалось по выдуманному адресу,
     * привязка сессии к IP теряла смысл, бан по адресу снимался одним заголовком.
     *
     * <p>Теперь решает адрес сокета: подделать его клиент не может. Не совпал с
     * перечнем — заголовок игнорируется целиком, и адресом считается адрес соединения.
     */
    public static @NotNull IpAddress clientIp(@NotNull HttpServletRequest request,
                                              @NotNull ConfigurationService config) {
        IpAddress peer = IpAddress.ofNullable(request.getRemoteAddr());

        if (!config.getBoolean(ConfigFile.VELOCITY, "forwarding.trust-proxy-header", false)) {
            return peer;
        }
        TrustedProxies trusted = trustedProxies(config);
        if (trusted.isEmpty()) {
            // Заголовку разрешили верить, но не сказали кому. Единственный безопасный
            // вывод — не верить никому: «доверять всем» здесь означало бы вернуть
            // ровно ту дыру, ради которой перечень и заводился.
            LOGGER.warn("forwarding.trust-proxy-header включён, но forwarding.trusted-proxies "
                    + "пуст: заголовок с адресом игнорируется");
            return peer;
        }
        if (!trusted.trusts(peer)) {
            LOGGER.debug("Заголовок с адресом от недоверенного узла {} проигнорирован",
                    peer.asMasked());
            return peer;
        }

        String headerName = config.getString(ConfigFile.VELOCITY,
                "forwarding.header-name", "X-Real-IP");
        String value = request.getHeader(headerName);
        if (value == null || value.isBlank()) {
            return peer;
        }
        // X-Forwarded-For может содержать цепочку: клиент, прокси1, прокси2.
        // Первый элемент — исходный клиент.
        IpAddress parsed = IpAddress.ofNullable(value.split(",", 2)[0].trim());
        return parsed.isUnknown() ? peer : parsed;
    }

    private static TrustedProxies trustedProxies(ConfigurationService config) {
        List<String> entries = config.getStringList(ConfigFile.VELOCITY,
                "forwarding.trusted-proxies");
        return PARSED.computeIfAbsent(String.join(",", entries),
                ignored -> TrustedProxies.parse(entries));
    }

    /** Контекст запроса для сервисов Core. */
    public static @NotNull AuthContext of(@NotNull HttpServletRequest request,
                                          @NotNull ConfigurationService config) {
        String userAgent = request.getHeader("User-Agent");
        return AuthContext.web(clientIp(request, config),
                userAgent == null ? null : trim(userAgent));
    }

    /** Обрезает User-Agent до ширины колонки. */
    private static String trim(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
