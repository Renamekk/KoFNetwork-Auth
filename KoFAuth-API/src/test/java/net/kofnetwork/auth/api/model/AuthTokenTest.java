package net.kofnetwork.auth.api.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthTokenTest {

    private static final String HASH = "a".repeat(64);
    private static final IpAddress IP = IpAddress.of("203.0.113.7");

    @Test
    void конструктор_требует_хэш_длиной_SHA256() {
        assertThatThrownBy(() -> AuthToken.issue(1L, TokenType.REFRESH, "слишком короткий", IP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    @Test
    void срок_жизни_берётся_из_типа_токена() {
        AuthToken reset = AuthToken.issue(1L, TokenType.PASSWORD_RESET, HASH, IP);

        Duration lifetime = Duration.between(reset.issuedAt(), reset.expiresAt());

        assertThat(lifetime).isEqualTo(TokenType.PASSWORD_RESET.defaultLifetime());
        assertThat(lifetime).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void свежий_токен_пригоден() {
        AuthToken token = AuthToken.issue(1L, TokenType.EMAIL_VERIFY, HASH, IP);

        assertThat(token.isUsable(Instant.now())).isTrue();
        assertThat(token.isReplay()).isFalse();
    }

    @Test
    void одноразовый_токен_становится_непригодным_после_использования() {
        AuthToken token = AuthToken.issue(1L, TokenType.PASSWORD_RESET, HASH, IP)
                .markUsed(Instant.now(), IP);

        assertThat(token.isUsable(Instant.now())).isFalse();
        assertThat(token.isReplay()).isTrue();
    }

    @Test
    void многоразовый_токен_остаётся_пригодным_после_использования() {
        AuthToken apiKey = AuthToken.issue(1L, TokenType.API_KEY, HASH, IP)
                .markUsed(Instant.now(), IP);

        assertThat(apiKey.isUsable(Instant.now())).isTrue();
        assertThat(apiKey.isReplay()).isFalse();
    }

    @Test
    void истёкший_токен_непригоден() {
        AuthToken token = AuthToken.issue(1L, TokenType.LOGIN_APPROVAL, HASH, IP);
        Instant afterExpiry = token.expiresAt().plusSeconds(1);

        assertThat(token.isUsable(afterExpiry)).isFalse();
        assertThat(token.isExpired(afterExpiry)).isTrue();
    }

    @Test
    void отозванный_токен_непригоден() {
        AuthToken token = AuthToken.issue(1L, TokenType.REFRESH, HASH, IP)
                .revoke(Instant.now());

        assertThat(token.isUsable(Instant.now())).isFalse();
    }

    @Test
    void повторный_отзыв_сохраняет_исходный_момент() {
        Instant first = Instant.now();
        AuthToken revoked = AuthToken.issue(1L, TokenType.REFRESH, HASH, IP).revoke(first);

        AuthToken again = revoked.revoke(first.plusSeconds(60));

        assertThat(again.revokedAt()).isEqualTo(first);
    }

    @Test
    void только_refresh_участвует_в_ротации() {
        assertThat(TokenType.REFRESH.isRotating()).isTrue();
        for (TokenType type : TokenType.values()) {
            if (type != TokenType.REFRESH) {
                assertThat(type.isRotating())
                        .as("тип %s не должен ротироваться", type)
                        .isFalse();
            }
        }
    }

    @Test
    void toString_показывает_лишь_префикс_хэша() {
        AuthToken token = AuthToken.issue(1L, TokenType.REFRESH, HASH, IP);

        assertThat(token.toString())
                .contains("aaaaaaaa...")
                .doesNotContain(HASH);
    }

    @Test
    void метаданные_неизменяемы() {
        AuthToken token = AuthToken.issue(1L, TokenType.TELEGRAM_LINK, HASH, IP)
                .withMetadata(java.util.Map.of("chatId", 12345L));

        assertThat(token.metadata()).isUnmodifiable();
        assertThat(token.metadata()).containsEntry("chatId", 12345L);
    }
}
