package net.kofnetwork.auth.core.security;

import net.kofnetwork.auth.api.exception.ConfigurationException;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.api.service.TokenService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private static final String SECRET = "a-secret-long-enough-for-hmac-sha512-signing";

    private final JwtProvider provider = new JwtProvider(SECRET, Duration.ofMinutes(15));

    @Test
    void выпускает_и_проверяет_токен() {
        String token = provider.issueAccessToken(42L, "Steve", "session-abc");

        OperationResult<TokenService.AccessClaims> result = provider.verify(token);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().accountId()).isEqualTo(42L);
        assertThat(result.value().username()).isEqualTo("Steve");
        assertThat(result.value().sessionPublicId()).isEqualTo("session-abc");
    }

    @Test
    void отвергает_токен_с_чужой_подписью() {
        // Реалистичная подделка: взять полезную нагрузку одного токена и
        // приложить к ней подпись от другого, выпущенного тем же ключом.
        //
        // Портить отдельный символ подписи для такой проверки нельзя: в base64
        // последний символ содержит неиспользуемые биты, и изменённая строка
        // может декодироваться в те же байты, то есть «испорченный» токен
        // окажется валидным, а тест — ложноположительным.
        String[] victim = provider.issueAccessToken(42L, "Steve", "session-abc").split("\\.");
        String[] attacker = provider.issueAccessToken(99L, "Alex", "session-xyz").split("\\.");
        String forged = victim[0] + "." + victim[1] + "." + attacker[2];

        OperationResult<TokenService.AccessClaims> result = provider.verify(forged);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorCode()).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void отвергает_токен_с_подменённой_полезной_нагрузкой() {
        // Классическая атака: поднять себе accountId, не трогая подпись.
        String[] parts = provider.issueAccessToken(42L, "Steve", "session-abc").split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        String elevated = payload.replace("\"42\"", "\"1\"");
        assertThat(elevated).isNotEqualTo(payload);

        String forged = parts[0] + "."
                + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(elevated.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThat(provider.verify(forged).isFailure()).isTrue();
    }

    @Test
    void отвергает_токен_с_алгоритмом_none() {
        // Историческая уязвимость реализаций JWT: принять токен, объявляющий alg=none,
        // и пропустить проверку подписи вовсе.
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[] parts = provider.issueAccessToken(42L, "Steve", "session-abc").split("\\.");

        assertThat(provider.verify(header + "." + parts[1] + ".").isFailure()).isTrue();
    }

    @Test
    void токен_выпущенный_другим_секретом_отвергается() {
        JwtProvider other = new JwtProvider("совершенно-другой-секрет-достаточной-длины-32+", Duration.ofMinutes(15));
        String foreign = other.issueAccessToken(42L, "Steve", "session-abc");

        assertThat(provider.verify(foreign).isFailure()).isTrue();
    }

    @Test
    void отвергает_истёкший_токен() throws InterruptedException {
        JwtProvider shortLived = new JwtProvider(SECRET, Duration.ofSeconds(1));
        String token = shortLived.issueAccessToken(42L, "Steve", "session-abc");

        // Проверяющий допускает расхождение часов в 5 секунд, поэтому ждём дольше.
        Thread.sleep(6_500);

        OperationResult<TokenService.AccessClaims> result = shortLived.verify(token);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorCode()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void отвергает_мусор_вместо_токена() {
        assertThat(provider.verify("не токен").isFailure()).isTrue();
        assertThat(provider.verify("").isFailure()).isTrue();
        assertThat(provider.verify("a.b.c").isFailure()).isTrue();
    }

    @Test
    void токен_содержит_идентификатор_сессии() {
        // Без него подпись подтверждала бы только происхождение токена,
        // но не то, что сессия ещё жива.
        String token = provider.issueAccessToken(1L, "Alex", "конкретная-сессия");

        assertThat(provider.verify(token).value().sessionPublicId()).isEqualTo("конкретная-сессия");
    }

    @Test
    void отвергает_слишком_короткий_секрет() {
        assertThatThrownBy(() -> new JwtProvider("коротко", Duration.ofMinutes(15)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("32");
    }

    @Test
    void отвергает_срок_жизни_больше_часа() {
        // JWT нельзя отозвать: длинный срок ломает «выйти со всех устройств».
        assertThatThrownBy(() -> new JwtProvider(SECRET, Duration.ofHours(2)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("отзыв");
    }

    @Test
    void отвергает_нулевой_срок_жизни() {
        assertThatThrownBy(() -> new JwtProvider(SECRET, Duration.ZERO))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void токены_для_разных_аккаунтов_различаются() {
        assertThat(provider.issueAccessToken(1L, "Steve", "s1"))
                .isNotEqualTo(provider.issueAccessToken(2L, "Steve", "s1"));
    }
}
