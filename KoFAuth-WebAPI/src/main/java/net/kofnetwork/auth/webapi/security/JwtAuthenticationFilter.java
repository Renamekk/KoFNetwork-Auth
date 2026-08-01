package net.kofnetwork.auth.webapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.webapi.util.RequestContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Опознание владельца запроса по access-токену.
 *
 * <p><b>Две проверки, а не одна.</b> Подпись JWT подтверждает, что токен выпустили
 * мы, но не что сессия ещё жива. Поэтому после проверки подписи сверяется состояние
 * сессии — иначе токен, выпущенный до смены пароля, продолжал бы работать до
 * истечения своих 15 минут, и «выйти со всех устройств» не срабатывало бы.
 *
 * <p>Порядок существенен: подпись проверяется первой и не требует обращения к
 * хранилищу, поэтому запросы с подделанным токеном отсекаются, не создавая
 * нагрузки на Redis.
 */
@Component
@Order(20)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final KoFAuthCore core;

    public JwtAuthenticationFilter(KoFAuthCore core) {
        this.core = core;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            // Анонимный запрос. Решение, пускать ли дальше, принимает контроллер:
            // часть эндпоинтов (вход, регистрация) анонимна по определению.
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(PREFIX.length()).trim();
        var claims = core.tokens().verifyAccessToken(token).join();
        if (claims.isFailure()) {
            chain.doFilter(request, response);
            return;
        }

        IpAddress ip = RequestContext.clientIp(request, core.config());
        var session = core.sessions()
                .validateByPublicId(claims.value().sessionPublicId(), ip)
                .join();

        if (session.isEmpty()) {
            // Подпись верна, но сессия отозвана или истекла.
            chain.doFilter(request, response);
            return;
        }

        request.setAttribute(AuthenticatedUser.ATTRIBUTE, new AuthenticatedUser(
                claims.value().accountId(),
                claims.value().username(),
                claims.value().sessionPublicId()));

        chain.doFilter(request, response);
    }

    /** Статику и Swagger фильтровать незачем. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/actuator/health")
                || !path.startsWith("/api/");
    }
}
