package net.kofnetwork.auth.webapi.security;

import jakarta.servlet.http.HttpServletRequest;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.webapi.util.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Доверие заголовку с адресом клиента.
 *
 * <p>Регрессия: заголовок принимался от кого угодно — проверялся только флаг
 * {@code trust-proxy-header}, а перечень {@code trusted-proxies} не использовался
 * вовсе. Достаточно было дотянуться до сервиса в обход обратного прокси, чтобы
 * подставить себе любой адрес и обойти ограничение скорости, привязку сессии к IP
 * и бан по адресу.
 */
class TrustedProxiesTest {

    @Nested
    @DisplayName("Разбор перечня")
    class Parsing {

        @Test
        @DisplayName("одиночный адрес совпадает только сам с собой")
        void одиночныйАдресСовпадаетСамССобой() {
            TrustedProxies trusted = TrustedProxies.parse(List.of("10.0.0.5"));

            assertThat(trusted.trusts(IpAddress.of("10.0.0.5"))).isTrue();
            assertThat(trusted.trusts(IpAddress.of("10.0.0.6"))).isFalse();
        }

        @Test
        @DisplayName("подсеть покрывает свои адреса и не покрывает чужие")
        void подсетьПокрываетСвоиАдреса() {
            TrustedProxies trusted = TrustedProxies.parse(List.of("172.20.0.0/16"));

            assertThat(trusted.trusts(IpAddress.of("172.20.0.1"))).isTrue();
            assertThat(trusted.trusts(IpAddress.of("172.20.255.254"))).isTrue();
            assertThat(trusted.trusts(IpAddress.of("172.21.0.1"))).isFalse();
        }

        @Test
        @DisplayName("граница префикса проверяется побитово")
        void границаПрефиксаПроверяетсяПобитово() {
            TrustedProxies trusted = TrustedProxies.parse(List.of("10.1.2.0/23"));

            assertThat(trusted.trusts(IpAddress.of("10.1.2.255"))).isTrue();
            assertThat(trusted.trusts(IpAddress.of("10.1.3.255"))).isTrue();
            assertThat(trusted.trusts(IpAddress.of("10.1.4.0"))).isFalse();
        }

        @Test
        @DisplayName("IPv6 разбирается и не путается с IPv4")
        void ipv6РазбираетсяИНеПутается() {
            TrustedProxies trusted = TrustedProxies.parse(List.of("::1/128"));

            assertThat(trusted.trusts(IpAddress.of("::1"))).isTrue();
            assertThat(trusted.trusts(IpAddress.of("127.0.0.1"))).isFalse();
        }

        @Test
        @DisplayName("нечитаемая запись пропускается, остальные работают")
        void нечитаемаяЗаписьПропускается() {
            // Одна опечатка не должна обнулять весь перечень — иначе правка
            // конфигурации превращалась бы в отказ доверять настоящему прокси.
            TrustedProxies trusted = TrustedProxies.parse(
                    List.of("не-адрес", "10.0.0.0/8", "10.0.0.0/99"));

            assertThat(trusted.trusts(IpAddress.of("10.1.1.1"))).isTrue();
            assertThat(trusted.trusts(IpAddress.of("192.168.0.1"))).isFalse();
        }

        @Test
        @DisplayName("пустой перечень не доверяет никому")
        void пустойПереченьНеДоверяетНикому() {
            TrustedProxies trusted = TrustedProxies.parse(List.of());

            assertThat(trusted.isEmpty()).isTrue();
            assertThat(trusted.trusts(IpAddress.of("127.0.0.1"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Определение адреса запроса")
    class ClientIp {

        private ConfigurationService config(boolean trustHeader, List<String> proxies) {
            ConfigurationService config = mock(ConfigurationService.class);
            when(config.getBoolean(any(ConfigFile.class), eq("forwarding.trust-proxy-header"),
                    anyBoolean())).thenReturn(trustHeader);
            when(config.getString(any(ConfigFile.class), eq("forwarding.header-name"), anyString()))
                    .thenReturn("X-Real-IP");
            when(config.getStringList(any(ConfigFile.class), eq("forwarding.trusted-proxies")))
                    .thenReturn(proxies);
            return config;
        }

        private HttpServletRequest request(String peer, String header) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn(peer);
            when(request.getHeader("X-Real-IP")).thenReturn(header);
            return request;
        }

        /** Регрессия: подмена адреса заголовком в обход прокси. */
        @Test
        @DisplayName("заголовок от недоверенного узла игнорируется")
        void заголовокОтНедоверенногоУзлаИгнорируется() {
            IpAddress ip = RequestContext.clientIp(
                    request("203.0.113.9", "1.2.3.4"),
                    config(true, List.of("10.0.0.0/8")));

            assertThat(ip)
                    .as("адрес сокета — единственное, что клиент подделать не может")
                    .isEqualTo(IpAddress.of("203.0.113.9"));
        }

        @Test
        @DisplayName("заголовок от доверенного прокси принимается")
        void заголовокОтДоверенногоПринимается() {
            IpAddress ip = RequestContext.clientIp(
                    request("10.0.0.7", "1.2.3.4"),
                    config(true, List.of("10.0.0.0/8")));

            assertThat(ip).isEqualTo(IpAddress.of("1.2.3.4"));
        }

        @Test
        @DisplayName("первый элемент цепочки — исходный клиент")
        void первыйЭлементЦепочкиИсходныйКлиент() {
            IpAddress ip = RequestContext.clientIp(
                    request("10.0.0.7", "1.2.3.4, 10.0.0.7, 10.0.0.8"),
                    config(true, List.of("10.0.0.0/8")));

            assertThat(ip).isEqualTo(IpAddress.of("1.2.3.4"));
        }

        @Test
        @DisplayName("пустой перечень доверенных выключает доверие заголовку")
        void пустойПереченьВыключаетДоверие() {
            // «Доверять всем» — не вариант по умолчанию, а отсутствующий вариант.
            IpAddress ip = RequestContext.clientIp(
                    request("10.0.0.7", "1.2.3.4"),
                    config(true, List.of()));

            assertThat(ip).isEqualTo(IpAddress.of("10.0.0.7"));
        }

        @Test
        @DisplayName("выключенное доверие игнорирует заголовок даже от доверенного узла")
        void выключенноеДовериеИгнорируетЗаголовок() {
            IpAddress ip = RequestContext.clientIp(
                    request("10.0.0.7", "1.2.3.4"),
                    config(false, List.of("10.0.0.0/8")));

            assertThat(ip).isEqualTo(IpAddress.of("10.0.0.7"));
        }

        @Test
        @DisplayName("отсутствующий заголовок оставляет адрес соединения")
        void отсутствующийЗаголовокОставляетАдресСоединения() {
            IpAddress ip = RequestContext.clientIp(
                    request("10.0.0.7", null),
                    config(true, List.of("10.0.0.0/8")));

            assertThat(ip).isEqualTo(IpAddress.of("10.0.0.7"));
        }

        @Test
        @DisplayName("неразбираемое значение заголовка не подменяет адрес")
        void неразбираемоеЗначениеНеПодменяетАдрес() {
            IpAddress ip = RequestContext.clientIp(
                    request("10.0.0.7", "не-адрес"),
                    config(true, List.of("10.0.0.0/8")));

            assertThat(ip).isEqualTo(IpAddress.of("10.0.0.7"));
        }
    }
}
