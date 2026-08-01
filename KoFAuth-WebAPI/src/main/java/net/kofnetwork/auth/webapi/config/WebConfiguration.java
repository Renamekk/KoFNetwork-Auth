package net.kofnetwork.auth.webapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.webapi.util.RequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.List;

/** Настройка веб-слоя: CORS, заголовки безопасности, ограничение скорости, Swagger. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    @Value("${kofauth.cors.allowed-origins:}")
    private List<String> allowedOrigins;

    /**
     * CORS.
     *
     * <p>Список источников задаётся явно. {@code allowedOrigins("*")} вместе с
     * {@code allowCredentials(true)} браузер отвергает, а без учётных данных наш
     * личный кабинет не работает — поэтому подстановочный вариант тут не годится
     * даже как «временное решение».
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("KoFAuth REST API")
                        .version("1.0.0")
                        .description("""
                                API системы авторизации сети KoF Network.

                                Все эндпоинты, кроме /api/auth/*, требуют заголовка
                                `Authorization: Bearer <access-токен>`.

                                Access-токен живёт 15 минут и обновляется через
                                `/api/auth/refresh`. Refresh-токен ротируется при
                                каждом использовании: предъявление уже использованного
                                означает утечку, и вся цепочка отзывается.
                                """))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    /**
     * Заголовки безопасности.
     *
     * <p>CSP запрещает исполнение стороннего кода: даже если в личный кабинет
     * попадёт чужой скрипт, браузер его не запустит. {@code X-Frame-Options}
     * закрывает clickjacking — иначе кабинет можно встроить в прозрачный фрейм
     * и подловить клик по кнопке «выйти со всех устройств».
     */
    @Component
    @Order(5)
    public static class SecurityHeadersFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            response.setHeader("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
            chain.doFilter(request, response);
        }
    }

    /**
     * Ограничение скорости на уровне HTTP.
     *
     * <p>Дублирует лимиты внутри Core, но отсекает раньше: запрос, отвергнутый
     * здесь, не создаёт ни соединения к базе, ни объекта запроса в сервисах.
     * Для перебора через API это разница между нагрузкой на фильтр и нагрузкой
     * на весь стек.
     */
    @Component
    @Order(10)
    public static class RateLimitFilter extends OncePerRequestFilter {

        private final KoFAuthCore core;

        public RateLimitFilter(KoFAuthCore core) {
            this.core = core;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            var ip = RequestContext.clientIp(request, core.config());
            var verdict = core.security().checkAndConsume("api.per-ip", ip.asString()).join();

            if (!verdict.allowed()) {
                // В Jakarta Servlet API нет константы для 429 — она появилась
                // в RFC 6585 позже, чем зафиксировался список SC_*.
                response.setStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                if (verdict.retryAfter() != null) {
                    response.setHeader("Retry-After",
                            String.valueOf(verdict.retryAfter().toSeconds()));
                }
                response.getWriter().write(
                        "{\"code\":\"RATE_LIMITED\",\"message\":\"Слишком много запросов\"}");
                return;
            }
            response.setHeader("X-RateLimit-Remaining", String.valueOf(verdict.remaining()));
            chain.doFilter(request, response);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/api/");
        }
    }
}
