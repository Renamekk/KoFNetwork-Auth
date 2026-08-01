package net.kofnetwork.auth.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    private final PasswordPolicy policy = PasswordPolicy.defaults();

    @Test
    void принимает_нормальный_пароль() {
        assertThat(policy.validate("Korovka42Luna", "Steve")).isEmpty();
        assertThat(policy.isAcceptable("Korovka42Luna", "Steve")).isTrue();
    }

    @Test
    void возвращает_все_нарушения_сразу_а_не_первое() {
        // Иначе игрок подбирает пароль пятью заходами и в итоге пишет его на бумажке.
        var issues = policy.validate("abc", "Steve");

        assertThat(issues).contains(
                PasswordPolicy.TOO_SHORT,
                PasswordPolicy.NO_UPPERCASE,
                PasswordPolicy.NO_DIGIT);
    }

    @Test
    void требует_минимальную_длину() {
        assertThat(policy.validate("Ab3", "Steve")).contains(PasswordPolicy.TOO_SHORT);
    }

    @Test
    void требует_буквы_обоих_регистров_и_цифру() {
        assertThat(policy.validate("korovkaluna", "Steve"))
                .contains(PasswordPolicy.NO_UPPERCASE, PasswordPolicy.NO_DIGIT);
        assertThat(policy.validate("KOROVKALUNA9", "Steve"))
                .contains(PasswordPolicy.NO_LOWERCASE);
    }

    @Test
    void спецсимвол_по_умолчанию_не_требуется() {
        assertThat(policy.validate("Korovka42Luna", "Steve"))
                .doesNotContain(PasswordPolicy.NO_SPECIAL);
    }

    @Test
    void спецсимвол_требуется_если_включено() {
        PasswordPolicy strict = PasswordPolicy.builder().requireSpecial(true).build();

        assertThat(strict.validate("Korovka42Luna", "Steve")).contains(PasswordPolicy.NO_SPECIAL);
        assertThat(strict.validate("Korovka42Luna!", "Steve")).isEmpty();
    }

    @Test
    void запрещает_пароль_содержащий_ник() {
        assertThat(policy.validate("Steve12345X", "Steve")).contains(PasswordPolicy.CONTAINS_USERNAME);
    }

    @Test
    void сравнение_с_ником_без_учёта_регистра() {
        assertThat(policy.validate("sTeVe12345X", "Steve")).contains(PasswordPolicy.CONTAINS_USERNAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "qwerty123", "minecraft", "passw0rd", "пароль"})
    void запрещает_частые_пароли(String common) {
        assertThat(policy.validate(common, "Steve")).contains(PasswordPolicy.TOO_COMMON);
    }

    @Test
    void запрещает_повторяющиеся_символы() {
        // Порог — четыре подряд ОДИНАКОВЫХ символа. Сравнение чувствительно
        // к регистру, поэтому "Aaaa" — это всего три подряд.
        assertThat(policy.validate("Baaaa9Xyz", "Steve"))
                .contains(PasswordPolicy.REPEATED_CHARACTERS);
    }

    @Test
    void три_повтора_ещё_допустимы() {
        assertThat(policy.validate("Baaa9Xyzq", "Steve"))
                .doesNotContain(PasswordPolicy.REPEATED_CHARACTERS);
    }

    @Test
    void запрещает_возрастающие_последовательности() {
        assertThat(policy.validate("Ab12345Xyz", "Steve"))
                .contains(PasswordPolicy.SEQUENTIAL_CHARACTERS);
    }

    @Test
    void запрещает_убывающие_последовательности() {
        assertThat(policy.validate("Ab54321Xyz", "Steve"))
                .contains(PasswordPolicy.SEQUENTIAL_CHARACTERS);
    }

    @Test
    void короткая_последовательность_допустима() {
        // Порог — пять подряд; "123" в середине нормального пароля не должен мешать.
        assertThat(policy.validate("Korovka123Luna", "Steve"))
                .doesNotContain(PasswordPolicy.SEQUENTIAL_CHARACTERS);
    }

    @Test
    void ограничение_длины_считается_в_байтах_UTF8() {
        // 37 кириллических символов — 74 байта, сверх предела BCrypt в 72.
        String cyrillic = "Пароль1" + "я".repeat(40);

        assertThat(policy.validate(cyrillic, "Steve")).contains(PasswordPolicy.TOO_LONG);
    }

    @Test
    void кириллический_пароль_в_пределах_лимита_принимается() {
        assertThat(policy.validate("Коровка42Луна", "Steve")).isEmpty();
    }

    @Test
    void настройки_политики_соблюдаются() {
        PasswordPolicy lenient = PasswordPolicy.builder()
                .minLength(4)
                .requireUppercase(false)
                .requireDigit(false)
                .forbidCommon(false)
                .forbidSequences(false)
                .forbidUsername(false)
                .build();

        assertThat(lenient.validate("abcd", "abcd")).isEmpty();
    }
}
