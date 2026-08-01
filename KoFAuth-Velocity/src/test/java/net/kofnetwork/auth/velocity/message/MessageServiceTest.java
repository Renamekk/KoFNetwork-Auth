package net.kofnetwork.auth.velocity.message;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.config.Reloadable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class MessageServiceTest {

    /**
     * Конфигурация в памяти.
     *
     * <p>Ручной двойник, а не мок: нужен изменяемый источник строк, чтобы
     * проверить поведение при перезагрузке, и три десятка заглушек мока
     * читались бы хуже, чем одна карта.
     */
    private static final class StubConfig implements ConfigurationService {

        private final Map<String, String> values = new HashMap<>();

        void put(ConfigFile file, String path, String value) {
            values.put(file + ":" + path, value);
        }

        @Override
        public @NotNull String getString(@NotNull ConfigFile file, @NotNull String path,
                                         @NotNull String fallback) {
            return values.getOrDefault(file + ":" + path, fallback);
        }

        @Override
        public @NotNull String getString(@NotNull ConfigFile file, @NotNull String path) {
            return getString(file, path, "");
        }

        @Override
        public int getInt(@NotNull ConfigFile file, @NotNull String path, int fallback) {
            return fallback;
        }

        @Override
        public long getLong(@NotNull ConfigFile file, @NotNull String path, long fallback) {
            return fallback;
        }

        @Override
        public boolean getBoolean(@NotNull ConfigFile file, @NotNull String path, boolean fallback) {
            return fallback;
        }

        @Override
        public @NotNull Duration getDuration(@NotNull ConfigFile file, @NotNull String path,
                                             @NotNull Duration fallback) {
            return fallback;
        }

        @Override
        public double getDouble(@NotNull ConfigFile file, @NotNull String path, double fallback) {
            return fallback;
        }

        @Override
        public @NotNull List<String> getStringList(@NotNull ConfigFile file, @NotNull String path) {
            return List.of();
        }

        @Override
        public @NotNull Map<String, Object> getSection(@NotNull ConfigFile file,
                                                       @NotNull String path) {
            return Map.of();
        }

        @Override
        public boolean contains(@NotNull ConfigFile file, @NotNull String path) {
            return values.containsKey(file + ":" + path);
        }

        @Override
        public @NotNull CompletableFuture<ReloadReport> reload() {
            return CompletableFuture.completedFuture(
                    new ReloadReport(true, List.of(), Map.of(), 0));
        }

        @Override
        public void registerReloadable(@NotNull Reloadable reloadable) {
        }

        @Override
        public void unregisterReloadable(@NotNull Reloadable reloadable) {
        }

        @Override
        public @NotNull Path configDirectory() {
            return Path.of(".");
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private final StubConfig config = new StubConfig();
    private final MessageService messages = new MessageService(config);

    @Test
    void запасной_текст_используется_когда_ключа_нет() {
        // Сообщение, забытое в конфигурации, не должно превращаться в пустую
        // строку: игрок увидел бы пустой чат вместо причины отказа.
        assertThat(plain(messages.plain("login-usage", "Использование: /login")))
                .isEqualTo("Использование: /login");
    }

    @Test
    void значение_из_конфигурации_перекрывает_запасное() {
        config.put(ConfigFile.CONFIG, "messages.wrong-password", "<red>Неверный пароль");

        assertThat(plain(messages.plain("wrong-password", "запасной")))
                .isEqualTo("Неверный пароль");
    }

    @Test
    void префикс_добавляется_в_начало() {
        config.put(ConfigFile.CONFIG, "messages.prefix", "<gray>[KoF] ");

        assertThat(plain(messages.prefixed("hello", "Привет")))
                .isEqualTo("[KoF] Привет");
    }

    @Test
    void подстановки_заменяются() {
        config.put(ConfigFile.CONFIG, "messages.attempts",
                "Осталось попыток: <attempts>");

        assertThat(plain(messages.prefixed("attempts", "", Map.of("attempts", "3"))))
                .isEqualTo("Осталось попыток: 3");
    }

    @Test
    void неизвестная_подстановка_остаётся_как_есть_и_не_ломает_разбор() {
        // MiniMessage игнорирует неизвестные теги, а не бросает исключение:
        // опечатка в конфигурации не должна выключать сообщение целиком.
        config.put(ConfigFile.CONFIG, "messages.oops", "Значение: <нет-такого>");

        assertThat(plain(messages.prefixed("oops", "", Map.of("attempts", "3"))))
                .contains("Значение:");
    }

    @Test
    void кик_сообщения_берутся_из_velocity_yml() {
        // Отдельный файл: кик-тексты правит тот, кто настраивает прокси,
        // и путать их с общими сообщениями сети не следует.
        config.put(ConfigFile.VELOCITY, "kick-messages.account-locked", "<red>Заблокирован");

        assertThat(plain(messages.kick("account-locked", "запасной")))
                .isEqualTo("Заблокирован");
    }

    @Test
    void перезагрузка_конфигурации_видна_сразу() {
        // Компоненты не кэшируются намеренно: закэшированный пережил бы
        // /auth reload и оставил на экране старый текст.
        config.put(ConfigFile.CONFIG, "messages.greeting", "Старое");
        assertThat(plain(messages.plain("greeting", ""))).isEqualTo("Старое");

        config.put(ConfigFile.CONFIG, "messages.greeting", "Новое");
        assertThat(plain(messages.plain("greeting", ""))).isEqualTo("Новое");
    }

    @Test
    void форматирование_не_попадает_в_текст() {
        // Теги обязаны стать оформлением, а не остаться видимыми символами.
        config.put(ConfigFile.CONFIG, "messages.styled",
                "<gradient:#5b6bff:#3ecf8e><bold>KoF Network</bold></gradient>");

        assertThat(plain(messages.plain("styled", "")))
                .isEqualTo("KoF Network")
                .doesNotContain("<");
    }
}
