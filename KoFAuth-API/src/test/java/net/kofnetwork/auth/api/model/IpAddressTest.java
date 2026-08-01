package net.kofnetwork.auth.api.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IpAddressTest {

    @Nested
    @DisplayName("разбор")
    class Parsing {

        @Test
        void разбирает_IPv4_в_четыре_байта() {
            IpAddress ip = IpAddress.of("192.168.1.10");

            assertThat(ip.toBytes()).hasSize(4);
            assertThat(ip.isIpv4()).isTrue();
            assertThat(ip.asString()).isEqualTo("192.168.1.10");
        }

        @Test
        void разбирает_IPv6_в_шестнадцать_байт() {
            IpAddress ip = IpAddress.of("2001:db8::1");

            assertThat(ip.toBytes()).hasSize(16);
            assertThat(ip.isIpv4()).isFalse();
        }

        @Test
        void нормализует_разные_записи_одного_IPv6_адреса() {
            // Ключевое свойство типа: сравнение по байтам, а не по строке.
            assertThat(IpAddress.of("::1")).isEqualTo(IpAddress.of("0:0:0:0:0:0:0:1"));
        }

        @Test
        void отбрасывает_порт_у_IPv4() {
            assertThat(IpAddress.of("1.2.3.4:25565")).isEqualTo(IpAddress.of("1.2.3.4"));
        }

        @Test
        void отбрасывает_порт_у_IPv6_в_скобках() {
            assertThat(IpAddress.of("[2001:db8::1]:25565")).isEqualTo(IpAddress.of("2001:db8::1"));
        }

        @Test
        void отбрасывает_зону_IPv6() {
            assertThat(IpAddress.of("fe80::1%eth0")).isEqualTo(IpAddress.of("fe80::1"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "example.com",        // имя хоста: DNS-запрос в горячем пути недопустим
                "mc.hypixel.net",
                "1.2.3",              // неполный адрес
                "1.2.3.4.5",
                "256.1.1.1",          // октет вне диапазона
                "1.2.3.abc",
                ""
        })
        void отвергает_всё_что_не_является_IP_литералом(String value) {
            assertThatThrownBy(() -> IpAddress.of(value))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void ofNullable_возвращает_UNKNOWN_вместо_исключения() {
            assertThat(IpAddress.ofNullable("не адрес")).isEqualTo(IpAddress.UNKNOWN);
            assertThat(IpAddress.ofNullable(null)).isEqualTo(IpAddress.UNKNOWN);
            assertThat(IpAddress.ofNullable("  ")).isEqualTo(IpAddress.UNKNOWN);
        }

        @Test
        void ofBytes_требует_длину_4_или_16() {
            assertThatThrownBy(() -> IpAddress.ofBytes(new byte[]{1, 2, 3}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("4 или 16");
        }
    }

    @Nested
    @DisplayName("неизменяемость")
    class Immutability {

        @Test
        void изменение_возвращённого_массива_не_затрагивает_адрес() {
            IpAddress ip = IpAddress.of("10.0.0.1");
            byte[] bytes = ip.toBytes();

            bytes[0] = 99;

            assertThat(ip.asString()).isEqualTo("10.0.0.1");
        }

        @Test
        void изменение_исходного_массива_не_затрагивает_адрес() {
            byte[] source = {10, 0, 0, 1};
            IpAddress ip = IpAddress.ofBytes(source);

            source[0] = 99;

            assertThat(ip.asString()).isEqualTo("10.0.0.1");
        }
    }

    @Nested
    @DisplayName("маскирование")
    class Masking {

        @Test
        void скрывает_последний_октет_IPv4() {
            assertThat(IpAddress.of("192.168.1.10").asMasked()).isEqualTo("192.168.1.***");
        }

        @Test
        void toString_возвращает_маскированный_адрес() {
            // Осознанное решение: toString слишком легко попадает в лог по недосмотру.
            IpAddress ip = IpAddress.of("192.168.1.10");

            assertThat(ip.toString()).isEqualTo(ip.asMasked());
            assertThat(ip.toString()).doesNotContain("192.168.1.10");
        }

        @Test
        void полный_адрес_доступен_через_asString() {
            assertThat(IpAddress.of("192.168.1.10").asString()).isEqualTo("192.168.1.10");
        }
    }

    @Nested
    @DisplayName("подсети")
    class Subnets {

        @Test
        void сводит_IPv4_к_маске_24() {
            IpAddress a = IpAddress.of("203.0.113.5");
            IpAddress b = IpAddress.of("203.0.113.200");

            assertThat(a.subnet()).isEqualTo(b.subnet());
            assertThat(a.subnet().asString()).isEqualTo("203.0.113.0");
        }

        @Test
        void различает_соседние_подсети_IPv4() {
            assertThat(IpAddress.of("203.0.113.5").subnet())
                    .isNotEqualTo(IpAddress.of("203.0.114.5").subnet());
        }

        @Test
        void сводит_IPv6_к_маске_64() {
            IpAddress a = IpAddress.of("2001:db8:0:1::1");
            IpAddress b = IpAddress.of("2001:db8:0:1::ffff");

            assertThat(a.subnet()).isEqualTo(b.subnet());
        }
    }

    @Nested
    @DisplayName("классификация")
    class Classification {

        @ParameterizedTest
        @ValueSource(strings = {"127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.1", "::1"})
        void распознаёт_локальные_и_приватные(String value) {
            assertThat(IpAddress.of(value).isLoopbackOrPrivate()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"8.8.8.8", "203.0.113.5", "2001:db8::1"})
        void распознаёт_публичные(String value) {
            assertThat(IpAddress.of(value).isLoopbackOrPrivate()).isFalse();
        }

        @Test
        void UNKNOWN_опознаётся() {
            assertThat(IpAddress.UNKNOWN.isUnknown()).isTrue();
            assertThat(IpAddress.of("1.2.3.4").isUnknown()).isFalse();
        }
    }

    @Test
    void равенство_и_хэш_считаются_по_байтам() {
        IpAddress a = IpAddress.of("1.2.3.4");
        IpAddress b = IpAddress.ofBytes(new byte[]{1, 2, 3, 4});

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
