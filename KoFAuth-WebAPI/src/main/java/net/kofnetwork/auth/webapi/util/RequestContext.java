package net.kofnetwork.auth.webapi.util;

import jakarta.servlet.http.HttpServletRequest;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.IpAddress;
import org.jetbrains.annotations.NotNull;

/** Извлечение адреса и контекста из HTTP-запроса. */
public final class RequestContext {

    private RequestContext() {
        throw new AssertionError("Утилитный класс не подлежит созданию");
    }

    /**
     * Адрес клиента.
     *
     * <p><b>Заголовку доверяем только если явно разрешено.</b> {@code X-Forwarded-For}
     * ставит кто угодно: без обратного прокси перед приложением клиент подставит
     * любой адрес и обойдёт и ограничение скорости, и привязку сессии к IP, и бан
     * по адресу. Поэтому по умолчанию берётся адрес сокета.
     */
    public static @NotNull IpAddress clientIp(@NotNull HttpServletRequest request,
                                              @NotNull ConfigurationService config) {
        boolean trustHeader = config.getBoolean(ConfigFile.VELOCITY,
                "forwarding.trust-proxy-header", false);
        if (trustHeader) {
            String headerName = config.getString(ConfigFile.VELOCITY,
                    "forwarding.header-name", "X-Real-IP");
            String value = request.getHeader(headerName);
            if (value != null && !value.isBlank()) {
                // X-Forwarded-For может содержать цепочку: клиент, прокси1, прокси2.
                // Первый элемент — исходный клиент.
                String first = value.split(",", 2)[0].trim();
                IpAddress parsed = IpAddress.ofNullable(first);
                if (!parsed.isUnknown()) {
                    return parsed;
                }
            }
        }
        return IpAddress.ofNullable(request.getRemoteAddr());
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
