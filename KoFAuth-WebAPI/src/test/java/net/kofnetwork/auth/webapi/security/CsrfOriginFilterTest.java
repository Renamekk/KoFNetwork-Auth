package net.kofnetwork.auth.webapi.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfOriginFilterTest {

    private static final String OWN = "https://kofnetwork.net";

    private CsrfOriginFilter filter(String... allowedOrigins) {
        CsrfOriginFilter filter = new CsrfOriginFilter();
        // Поле заполняется Spring через @Value; в модульном тесте контейнера нет.
        ReflectionTestUtils.setField(filter, "allowedOrigins", List.of(allowedOrigins));
        return filter;
    }

    /** Запрос к API с указанного источника. */
    private static MockHttpServletRequest request(String method, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/auth/login");
        request.setScheme("https");
        request.setServerName("kofnetwork.net");
        request.setServerPort(443);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    private static boolean passedThrough(FilterChain chain) {
        return ((MockFilterChain) chain).getRequest() != null;
    }

    @Test
    void чужой_источник_отвергается() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("POST", "https://evil.example"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("CSRF_ORIGIN_REJECTED");
        // Главное: до контроллера запрос не дошёл. Кода ответа мало — важно,
        // что действие не выполнилось.
        assertThat(passedThrough(chain)).isFalse();
    }

    @Test
    void свой_источник_пропускается() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("POST", OWN), new MockHttpServletResponse(), chain);

        assertThat(passedThrough(chain)).isTrue();
    }

    @Test
    void явно_разрешённый_сторонний_источник_пропускается() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter("https://cabinet.kofnetwork.net")
                .doFilter(request("POST", "https://cabinet.kofnetwork.net"),
                        new MockHttpServletResponse(), chain);

        assertThat(passedThrough(chain)).isTrue();
    }

    @Test
    void запрос_без_источника_пропускается() throws Exception {
        // Не-браузерный клиент: игровой сервер, curl, мобильное приложение.
        // Требовать от них заголовок — сломать их, ничего не выиграв.
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("POST", null), new MockHttpServletResponse(), chain);

        assertThat(passedThrough(chain)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void читающие_методы_не_проверяются(String method) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request(method, "https://evil.example"),
                new MockHttpServletResponse(), chain);

        assertThat(passedThrough(chain)).isTrue();
    }

    @Test
    void явный_порт_по_умолчанию_совпадает_с_неявным() throws Exception {
        // https://host и https://host:443 — один источник. Строковое сравнение
        // отвергло бы половину настроек.
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("POST", "https://kofnetwork.net:443"),
                new MockHttpServletResponse(), chain);

        assertThat(passedThrough(chain)).isTrue();
    }

    @Test
    void другая_схема_на_том_же_хосте_отвергается() throws Exception {
        // http и https — разные источники: по http страницу может подменить
        // кто угодно на пути, и доверять ей нельзя.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("POST", "http://kofnetwork.net"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(passedThrough(chain)).isFalse();
    }

    @Test
    void поддомен_не_считается_своим() throws Exception {
        // Захваченный поддомен — обычный сценарий; без точного сравнения хоста
        // он получил бы доступ к API от имени основного домена.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("DELETE", "https://blog.kofnetwork.net"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(passedThrough(chain)).isFalse();
    }

    @Test
    void статика_не_фильтруется() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/index.html");

        Boolean skipped = ReflectionTestUtils.invokeMethod(filter(), "shouldNotFilter", request);

        assertThat(skipped).isTrue();
    }
}
