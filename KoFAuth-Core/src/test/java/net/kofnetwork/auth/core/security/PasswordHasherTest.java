package net.kofnetwork.auth.core.security;

import net.kofnetwork.auth.api.exception.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHasherTest {

    /** Минимально допустимая стоимость: тесты не должны ждать по 250 мс на хэш. */
    private static final int TEST_COST = 10;

    private final PasswordHasher hasher = new PasswordHasher(TEST_COST);

    @Test
    void хэширует_и_проверяет_пароль() {
        String hash = hasher.hash("Правильный1Пароль");

        assertThat(hasher.verify("Правильный1Пароль", hash)).isTrue();
        assertThat(hasher.verify("Неправильный1Пароль", hash)).isFalse();
    }

    @Test
    void одинаковые_пароли_дают_разные_хэши() {
        // Соль случайна, иначе одинаковые пароли были бы видны в дампе базы.
        String first = hasher.hash("ОдинИТотЖе1");
        String second = hasher.hash("ОдинИТотЖе1");

        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.verify("ОдинИТотЖе1", first)).isTrue();
        assertThat(hasher.verify("ОдинИТотЖе1", second)).isTrue();
    }

    @Test
    void проверка_чувствительна_к_регистру() {
        String hash = hasher.hash("Пароль1");

        assertThat(hasher.verify("пароль1", hash)).isFalse();
    }

    @Test
    void хэш_умещается_в_колонку_и_содержит_стоимость() {
        String hash = hasher.hash("Пароль1");

        // Колонка users.password_hash — VARCHAR(100).
        assertThat(hash).hasSizeLessThanOrEqualTo(100);
        assertThat(hash).startsWith("$2a$10$");
    }

    @Test
    void повреждённый_хэш_не_роняет_проверку() {
        // Испорченная строка в базе не должна валить обработку входа.
        assertThat(hasher.verify("Пароль1", "не хэш вовсе")).isFalse();
        assertThat(hasher.verify("Пароль1", "")).isFalse();
        assertThat(hasher.verify("Пароль1", "$2a$")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 9, 17, 31})
    void отвергает_стоимость_вне_допустимого_диапазона(int cost) {
        assertThatThrownBy(() -> new PasswordHasher(cost))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void отвергает_пароль_длиннее_предела_BCrypt() {
        // Молча обрезать нельзя: пользователь считал бы, что защищена вся фраза.
        String tooLong = "a".repeat(PasswordHasher.MAX_PASSWORD_BYTES + 1);

        assertThatThrownBy(() -> hasher.hash(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("72");
    }

    @Test
    void предел_считается_в_байтах_а_не_в_символах() {
        // Кириллица — два байта на символ, поэтому 37 букв уже превышают 72 байта.
        String cyrillic = "п".repeat(37);
        assertThat(cyrillic.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(PasswordHasher.MAX_PASSWORD_BYTES);

        assertThatThrownBy(() -> hasher.hash(cyrillic))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void пароль_ровно_на_пределе_принимается() {
        String exact = "a".repeat(PasswordHasher.MAX_PASSWORD_BYTES);

        String hash = hasher.hash(exact);

        assertThat(hasher.verify(exact, hash)).isTrue();
    }

    @Test
    void needsRehash_срабатывает_при_повышении_стоимости() {
        String weakHash = new PasswordHasher(10).hash("Пароль1");

        assertThat(new PasswordHasher(12).needsRehash(weakHash)).isTrue();
        assertThat(new PasswordHasher(10).needsRehash(weakHash)).isFalse();
        // Понижение стоимости перехэширования не требует.
        assertThat(new PasswordHasher(10).needsRehash(new PasswordHasher(12).hash("Пароль1"))).isFalse();
    }

    @Test
    void needsRehash_не_падает_на_повреждённом_хэше() {
        assertThat(hasher.needsRehash("мусор")).isFalse();
        assertThat(hasher.needsRehash("")).isFalse();
    }

    @Test
    void извлекает_стоимость_из_строки_хэша() {
        assertThat(PasswordHasher.extractCost("$2a$12$abcdefghijklmnopqrstuv")).isEqualTo(12);
        assertThat(PasswordHasher.extractCost("$2a$04$abcdefghijklmnopqrstuv")).isEqualTo(4);
        assertThat(PasswordHasher.extractCost("мусор")).isEqualTo(-1);
    }

    @Test
    void wasteTime_выполняется_и_ничего_не_ломает() {
        // Смысл метода — потратить столько же времени, сколько настоящая проверка.
        // Точное измерение в юнит-тесте ненадёжно; проверяем, что вызов безопасен
        // и сопоставим по порядку величины с реальной проверкой.
        String hash = hasher.hash("Пароль1");

        long realStart = System.nanoTime();
        hasher.verify("Неверный1", hash);
        long realNanos = System.nanoTime() - realStart;

        long wasteStart = System.nanoTime();
        hasher.wasteTime();
        long wasteNanos = System.nanoTime() - wasteStart;

        // Обе операции — один verify той же стоимости, различие не должно быть
        // кратным. Порог широкий: на загруженной машине разброс велик.
        assertThat(wasteNanos).isLessThan(realNanos * 10);
    }
}
