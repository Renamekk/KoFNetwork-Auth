package net.kofnetwork.auth.api.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptchaChallengeTest {

    private static final IpAddress IP = IpAddress.of("203.0.113.7");
    private static final String ANSWER_HASH = "b".repeat(64);

    private static CaptchaChallenge issue(int maxAttempts) {
        return CaptchaChallenge.issue(1L, UUID.randomUUID(), CaptchaType.GUI_GRID,
                ANSWER_HASH, IP, maxAttempts, Duration.ofMinutes(2));
    }

    @Test
    void новый_челлендж_принимает_ответы() {
        CaptchaChallenge challenge = issue(3);

        assertThat(challenge.status()).isEqualTo(CaptchaStatus.PENDING);
        assertThat(challenge.isAnswerable(Instant.now())).isTrue();
        assertThat(challenge.remainingAttempts()).isEqualTo(3);
    }

    @Test
    void неверная_попытка_уменьшает_остаток() {
        CaptchaChallenge after = issue(3).failAttempt(Instant.now());

        assertThat(after.remainingAttempts()).isEqualTo(2);
        assertThat(after.status()).isEqualTo(CaptchaStatus.PENDING);
        assertThat(after.isAnswerable(Instant.now())).isTrue();
    }

    @Test
    void исчерпание_попыток_переводит_в_FAILED() {
        Instant now = Instant.now();
        CaptchaChallenge exhausted = issue(2)
                .failAttempt(now)
                .failAttempt(now);

        assertThat(exhausted.status()).isEqualTo(CaptchaStatus.FAILED);
        assertThat(exhausted.remainingAttempts()).isZero();
        assertThat(exhausted.isAnswerable(now)).isFalse();
        assertThat(exhausted.resolvedAt()).isEqualTo(now);
    }

    @Test
    void просроченный_челлендж_не_принимает_ответы() {
        CaptchaChallenge challenge = issue(3);
        Instant afterExpiry = challenge.expiresAt().plusSeconds(1);

        assertThat(challenge.isAnswerable(afterExpiry)).isFalse();
    }

    @Test
    void прохождение_фиксирует_момент() {
        Instant now = Instant.now();

        CaptchaChallenge passed = issue(3).pass(now);

        assertThat(passed.status()).isEqualTo(CaptchaStatus.PASSED);
        assertThat(passed.resolvedAt()).isEqualTo(now);
        assertThat(passed.status().isTerminal()).isTrue();
    }

    @Test
    void идентификатор_челленджа_уникален() {
        assertThat(issue(3).challengeId()).isNotEqualTo(issue(3).challengeId());
    }

    @Test
    void конструктор_отвергает_нулевой_лимит_попыток() {
        assertThatThrownBy(() -> CaptchaChallenge.issue(1L, UUID.randomUUID(), CaptchaType.TEXT_INPUT,
                ANSWER_HASH, IP, 0, Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void только_PENDING_считается_незавершённым() {
        assertThat(CaptchaStatus.PENDING.isTerminal()).isFalse();
        assertThat(CaptchaStatus.PASSED.isTerminal()).isTrue();
        assertThat(CaptchaStatus.FAILED.isTerminal()).isTrue();
        assertThat(CaptchaStatus.EXPIRED.isTerminal()).isTrue();
    }
}
