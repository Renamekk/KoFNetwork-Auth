package net.kofnetwork.auth.core.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenGeneratorTest {

    @Test
    void токен_имеет_запрошенную_длину_в_hex() {
        assertThat(TokenGenerator.randomToken(32)).hasSize(64);
        assertThat(TokenGenerator.randomToken(16)).hasSize(32);
        assertThat(TokenGenerator.randomToken()).hasSize(64);
    }

    @Test
    void токены_не_повторяются() {
        Set<String> tokens = new HashSet<>();
        IntStream.range(0, 1000).forEach(i -> tokens.add(TokenGenerator.randomToken()));

        assertThat(tokens).hasSize(1000);
    }

    @Test
    void отвергает_слишком_короткий_токен() {
        assertThatThrownBy(() -> TokenGenerator.randomToken(8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");
    }

    @Test
    void код_для_человека_не_содержит_похожих_символов() {
        // 0/O и 1/I путают при переписывании с экрана телефона.
        // L оставлен намеренно: алфавит целиком в верхнем регистре,
        // и спутать его не с чем.
        String allCodes = IntStream.range(0, 200)
                .mapToObj(i -> TokenGenerator.humanReadableCode(8))
                .reduce("", String::concat);

        assertThat(allCodes).doesNotContain("0", "1", "O", "I");
    }

    @Test
    void код_для_человека_имеет_нужную_длину() {
        assertThat(TokenGenerator.humanReadableCode(8)).hasSize(8);
        assertThat(TokenGenerator.humanReadableCode(6)).hasSize(6);
    }

    @Test
    void отвергает_слишком_короткий_код() {
        assertThatThrownBy(() -> TokenGenerator.humanReadableCode(5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void числовой_код_состоит_только_из_цифр() {
        String code = TokenGenerator.numericCode(6);

        assertThat(code).hasSize(6).containsOnlyDigits();
    }

    @Test
    void резервный_код_разбит_на_группы() {
        String code = TokenGenerator.recoveryCode();

        assertThat(code).hasSize(14);
        assertThat(code.charAt(4)).isEqualTo('-');
        assertThat(code.charAt(9)).isEqualTo('-');
    }

    @Test
    void хэш_детерминирован_и_имеет_длину_SHA256() {
        String token = TokenGenerator.randomToken();

        String first = TokenGenerator.hash(token);
        String second = TokenGenerator.hash(token);

        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void разные_токены_дают_разные_хэши() {
        assertThat(TokenGenerator.hash("a")).isNotEqualTo(TokenGenerator.hash("b"));
    }

    @Test
    void сравнение_в_постоянном_времени_работает_как_равенство() {
        assertThat(TokenGenerator.constantTimeEquals("одинаково", "одинаково")).isTrue();
        assertThat(TokenGenerator.constantTimeEquals("одинаково", "по-другому")).isFalse();
        assertThat(TokenGenerator.constantTimeEquals("", "")).isTrue();
        // Разная длина не должна ронять сравнение.
        assertThat(TokenGenerator.constantTimeEquals("короткий", "гораздо длиннее")).isFalse();
    }

    @Test
    void nonce_уникален() {
        Set<String> nonces = new HashSet<>();
        IntStream.range(0, 500).forEach(i -> nonces.add(TokenGenerator.nonce()));

        assertThat(nonces).hasSize(500);
    }
}
