package net.kofnetwork.auth.webapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

/**
 * Защита от подделки межсайтового запроса.
 *
 * <p><b>Почему проверка источника, а не токен в форме.</b> Классический CSRF-токен
 * нужен там, где браузер сам прикладывает к запросу учётные данные — cookie или
 * Basic-авторизацию. Наш кабинет держит access-токен в {@code sessionStorage} и
 * ставит его заголовком вручную, поэтому запрос со стороннего сайта уходит вообще
 * без авторизации и отвергается на общих основаниях. Токен в форме защищал бы от
 * того, что уже невозможно.
 *
 * <p>Проверка источника закрывает две вещи, которые всё-таки возможны:
 *
 * <ul>
 *   <li><b>Ошибка в списке CORS.</b> {@code allowCredentials(true)} вместе со
 *       случайно расширенным списком источников делает чужую страницу способной
 *       читать ответы API. Здесь запрос отсекается до контроллера, независимо
 *       от того, что разрешил CORS.</li>
 *   <li><b>Возврат к cookie.</b> Если однажды токен переедет в cookie — ради
 *       {@code HttpOnly} или общего домена, — приложение окажется уязвимым
 *       мгновенно и молча. Этот фильтр не даст такому изменению пройти незаметно.</li>
 * </ul>
 *
 * <p><b>Запрос без {@code Origin} пропускается.</b> Браузер обязан присылать
 * заголовок для всех межсайтовых запросов, меняющих состояние, поэтому его
 * отсутствие означает не-браузерного клиента: игровой сервер, curl, мобильное
 * приложение. Требовать заголовок от них значит сломать их без выигрыша в защите —
 * подделать межсайтовый запрос вне браузера не нужно, там и так можно послать любой.
 */
@Component
@Order(15)
public class CsrfOriginFilter extends OncePerRequestFilter {

    /** Методы, меняющие состояние. Читающие проверять незачем. */
    private static final Set<String> PROTECTED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Value("${kofauth.cors.allowed-origins:}")
    private List<String> allowedOrigins;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (!PROTECTED_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        if (isAllowed(origin, request)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":\"CSRF_ORIGIN_REJECTED\",\"message\":\"Запрос отклонён: чужой источник\"}");
    }

    /**
     * Свой ли источник.
     *
     * <p>Сравнение по схеме, хосту и порту, а не по строке: {@code https://kof.net}
     * и {@code https://kof.net:443} — один и тот же источник, но разные строки, и
     * строковое сравнение отвергло бы половину настроек.
     */
    private boolean isAllowed(String origin, HttpServletRequest request) {
        if (allowedOrigins != null && allowedOrigins.stream()
                .anyMatch(allowed -> sameOrigin(allowed, origin))) {
            return true;
        }
        // Список пуст либо источника в нём нет: остаётся тот, что раздаёт кабинет.
        // requestURL строится из заголовка Host, но подменить его так, чтобы он
        // совпал с чужим Origin, значит подменить и сам Origin — выигрыша нет.
        return sameOrigin(request.getRequestURL().toString(), origin);
    }

    private static boolean sameOrigin(String left, String right) {
        try {
            URI a = new URI(left);
            URI b = new URI(right);
            return a.getScheme() != null
                    && a.getScheme().equalsIgnoreCase(b.getScheme())
                    && a.getHost() != null
                    && a.getHost().equalsIgnoreCase(b.getHost())
                    && port(a) == port(b);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Порт по умолчанию для схемы: явный 443 у https обязан совпасть с неявным. */
    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** Статика и Swagger состояния не меняют. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}
