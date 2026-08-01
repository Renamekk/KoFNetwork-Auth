package net.kofnetwork.auth.api.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionTest {

    private static final IpAddress IP = IpAddress.of("203.0.113.7");

    private static Session newSession(Duration sliding, Duration absolute) {
        return Session.create(1L, null, SessionType.GAME, IP, "vanilla", sliding, absolute);
    }

    @Test
    void новая_сессия_действительна() {
        Session session = newSession(Duration.ofHours(1), Duration.ofDays(7));

        assertThat(session.isValid(Instant.now())).isTrue();
        assertThat(session.revoked()).isFalse();
        assertThat(session.publicId()).isNotBlank();
    }

    @Test
    void публичный_идентификатор_уникален() {
        Session a = newSession(Duration.ofHours(1), Duration.ofDays(7));
        Session b = newSession(Duration.ofHours(1), Duration.ofDays(7));

        assertThat(a.publicId()).isNotEqualTo(b.publicId());
    }

    @Test
    void сессия_недействительна_после_истечения_скользящего_срока() {
        Session session = newSession(Duration.ofMinutes(30), Duration.ofDays(7));

        assertThat(session.isValid(Instant.now().plus(Duration.ofMinutes(31)))).isFalse();
    }

    @Test
    void продление_сдвигает_скользящий_срок() {
        Session session = newSession(Duration.ofMinutes(30), Duration.ofDays(7));
        Instant later = Instant.now().plus(Duration.ofMinutes(20));

        Session touched = session.touch(later, Duration.ofMinutes(30));

        assertThat(touched.expiresAt()).isAfter(session.expiresAt());
        assertThat(touched.isValid(later.plus(Duration.ofMinutes(25)))).isTrue();
    }

    @Test
    void продление_не_может_превысить_жёсткий_потолок() {
        // Ключевое свойство: иначе угнанную сессию можно «прогревать» бесконечно.
        Session session = newSession(Duration.ofHours(1), Duration.ofHours(2));
        Instant nearCeiling = session.absoluteExpiresAt().minus(Duration.ofMinutes(5));

        Session touched = session.touch(nearCeiling, Duration.ofHours(1));

        assertThat(touched.expiresAt()).isEqualTo(session.absoluteExpiresAt());
        assertThat(touched.isValid(session.absoluteExpiresAt().plusSeconds(1))).isFalse();
    }

    @Test
    void сессия_недействительна_после_жёсткого_потолка_даже_при_активности() {
        Session session = newSession(Duration.ofHours(1), Duration.ofHours(2));
        Instant beyond = session.absoluteExpiresAt().plusSeconds(1);

        Session touched = session.touch(beyond, Duration.ofHours(1));

        assertThat(touched.isValid(beyond)).isFalse();
    }

    @Test
    void отзыв_фиксирует_причину_и_момент() {
        Session session = newSession(Duration.ofHours(1), Duration.ofDays(7));
        Instant at = Instant.now();

        Session revoked = session.revoke(at, Session.REASON_PASSWORD_CHANGED);

        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.revokedAt()).isEqualTo(at);
        assertThat(revoked.revokedReason()).isEqualTo(Session.REASON_PASSWORD_CHANGED);
        assertThat(revoked.isValid(at)).isFalse();
    }

    @Test
    void повторный_отзыв_сохраняет_исходную_причину() {
        Instant first = Instant.now();
        Session revoked = newSession(Duration.ofHours(1), Duration.ofDays(7))
                .revoke(first, Session.REASON_LOGOUT);

        Session again = revoked.revoke(first.plusSeconds(60), Session.REASON_ADMIN);

        assertThat(again.revokedReason()).isEqualTo(Session.REASON_LOGOUT);
        assertThat(again.revokedAt()).isEqualTo(first);
    }

    @Test
    void сверка_адреса() {
        Session session = newSession(Duration.ofHours(1), Duration.ofDays(7));

        assertThat(session.matchesIp(IP)).isTrue();
        assertThat(session.matchesIp(IpAddress.of("198.51.100.1"))).isFalse();
    }

    @Test
    void потолок_меньше_скользящего_срока_поднимается_до_него() {
        // Конфигурация с absolute < sliding — ошибка администратора, но она не должна
        // приводить к отказу: сессия просто живёт ровно sliding.
        Session session = newSession(Duration.ofDays(7), Duration.ofHours(1));

        assertThat(session.absoluteExpiresAt()).isEqualTo(session.expiresAt());
    }

    @Test
    void конструктор_отвергает_потолок_раньше_скользящего_срока() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> new Session(0L, 1L, null, "id", SessionType.GAME, IP,
                null, null, null, null,
                now, now, now.plusSeconds(3600), now.plusSeconds(60),
                false, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
