package net.kofnetwork.auth.core.config;

import net.kofnetwork.auth.api.exception.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlDocumentTest {

    @TempDir
    Path tempDir;

    private YamlDocument write(String yaml) throws IOException {
        Path file = tempDir.resolve("test.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return YamlDocument.load(file);
    }

    @Test
    void читает_вложенные_значения_по_точечному_пути() throws IOException {
        YamlDocument doc = write("""
                mysql:
                  pool:
                    maximum-size: 10
                    connection-timeout: 10s
                  host: localhost
                """);

        assertThat(doc.getInt("mysql.pool.maximum-size", 0)).isEqualTo(10);
        assertThat(doc.getString("mysql.host")).isEqualTo("localhost");
        assertThat(doc.getDuration("mysql.pool.connection-timeout", Duration.ZERO))
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void возвращает_значение_по_умолчанию_для_отсутствующего_пути() throws IOException {
        YamlDocument doc = write("a: 1");

        assertThat(doc.getInt("нет.такого.пути", 42)).isEqualTo(42);
        assertThat(doc.getString("нет.такого", "запасное")).isEqualTo("запасное");
        assertThat(doc.getBoolean("нет.такого", true)).isTrue();
        assertThat(doc.contains("нет.такого")).isFalse();
    }

    @Test
    void не_падает_когда_промежуточный_сегмент_не_является_секцией() throws IOException {
        YamlDocument doc = write("a: просто строка");

        assertThat(doc.get("a.b.c")).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "30s, 30",
            "15m, 900",
            "2h,  7200",
            "1d,  86400",
            "45,  45"
    })
    void разбирает_человекочитаемые_длительности(String raw, long expectedSeconds) {
        assertThat(YamlDocument.parseDuration(raw, "путь"))
                .isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    void отвергает_неизвестную_единицу_длительности() {
        assertThatThrownBy(() -> YamlDocument.parseDuration("30x", "путь"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Неизвестная единица");
    }

    @Test
    void отвергает_некорректную_длительность() {
        assertThatThrownBy(() -> YamlDocument.parseDuration("абвs", "путь"))
                .isInstanceOf(ConfigurationException.class);
        assertThatThrownBy(() -> YamlDocument.parseDuration("-5s", "путь"))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void логическое_значение_разбирается_строго() throws IOException {
        // Опечатка не должна молча включать отключаемую защиту.
        YamlDocument doc = write("""
                yes-value: true
                no-value: false
                typo: flase
                """);

        assertThat(doc.getBoolean("yes-value", false)).isTrue();
        assertThat(doc.getBoolean("no-value", true)).isFalse();
        assertThatThrownBy(() -> doc.getBoolean("typo", false))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("true или false");
    }

    @Test
    void нечисловое_значение_числового_ключа_даёт_понятную_ошибку() throws IOException {
        YamlDocument doc = write("size: много");

        assertThatThrownBy(() -> doc.getInt("size", 0))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("size");
    }

    @Test
    void читает_списки() throws IOException {
        YamlDocument doc = write("""
                servers:
                  - limbo-1
                  - limbo-2
                single: только-один
                """);

        assertThat(doc.getStringList("servers")).containsExactly("limbo-1", "limbo-2");
        // Одиночное значение вместо списка — частая опечатка, принимаем как список из одного.
        assertThat(doc.getStringList("single")).containsExactly("только-один");
        assertThat(doc.getStringList("нет")).isEmpty();
    }

    @Test
    void читает_секции() throws IOException {
        YamlDocument doc = write("""
                properties:
                  useSSL: false
                  connectionTimeZone: UTC
                """);

        assertThat(doc.getSection("properties"))
                .containsEntry("useSSL", false)
                .containsEntry("connectionTimeZone", "UTC");
        assertThat(doc.getSection("нет")).isEmpty();
    }

    @Test
    void пустой_файл_даёт_пустой_документ() throws IOException {
        assertThat(write("").isEmpty()).isTrue();
        assertThat(write("# только комментарий").isEmpty()).isTrue();
    }

    @Test
    void отвергает_синтаксическую_ошибку() {
        assertThatThrownBy(() -> write("""
                a: [не закрытый
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Синтаксическая ошибка");
    }

    @Test
    void отвергает_дублирующиеся_ключи() {
        // Молча выиграл бы последний, и администратор правил бы «неработающую» строку.
        assertThatThrownBy(() -> write("""
                key: первое
                key: второе
                """))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void отвергает_корень_не_являющийся_отображением() {
        assertThatThrownBy(() -> write("- просто\n- список\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("отображением");
    }

    @Test
    void не_создаёт_произвольные_java_объекты_по_тегу() {
        // SafeConstructor: разбор YAML с тегом !! — известный путь к выполнению кода.
        assertThatThrownBy(() -> write("value: !!javax.script.ScriptEngineManager []"))
                .isInstanceOf(ConfigurationException.class);
    }
}
