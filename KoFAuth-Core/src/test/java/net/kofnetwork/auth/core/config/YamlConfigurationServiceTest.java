package net.kofnetwork.auth.core.config;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.config.Reloadable;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class YamlConfigurationServiceTest {

    @TempDir
    Path configDir;

    /** Синхронный executor: тест не должен зависеть от планировщика. */
    private static final java.util.concurrent.Executor DIRECT = Runnable::run;

    @BeforeEach
    void writeDefaults() throws IOException {
        write(ConfigFile.DATABASE, """
                mysql:
                  host: localhost
                  port: 3306
                  password: "из-файла"
                  pool:
                    maximum-size: 10
                    connection-timeout: 10s
                redis:
                  enabled: true
                """);
        write(ConfigFile.SECURITY, """
                password:
                  bcrypt-cost: 12
                rate-limit:
                  enabled: true
                """);
    }

    private void write(ConfigFile file, String content) throws IOException {
        Files.writeString(configDir.resolve(file.fileName()), content, StandardCharsets.UTF_8);
    }

    private YamlConfigurationService service(Map<String, String> environment) {
        YamlConfigurationService service =
                new YamlConfigurationService(configDir, DIRECT, environment);
        service.initialize();
        return service;
    }

    @Test
    void читает_значения_из_файла() {
        ConfigurationService config = service(Map.of());

        assertThat(config.getString(ConfigFile.DATABASE, "mysql.host", "")).isEqualTo("localhost");
        assertThat(config.getInt(ConfigFile.DATABASE, "mysql.pool.maximum-size", 0)).isEqualTo(10);
        assertThat(config.getDuration(ConfigFile.DATABASE, "mysql.pool.connection-timeout", Duration.ZERO))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(config.getBoolean(ConfigFile.DATABASE, "redis.enabled", false)).isTrue();
    }

    @Test
    void переменная_окружения_перекрывает_файл() {
        // В контейнерном развёртывании секреты приходят из окружения,
        // и файл в образе не должен их переопределять.
        ConfigurationService config = service(Map.of(
                "KOFAUTH_DATABASE_MYSQL_PASSWORD", "из-окружения"));

        assertThat(config.getString(ConfigFile.DATABASE, "mysql.password", ""))
                .isEqualTo("из-окружения");
    }

    @Test
    void имя_переменной_строится_по_правилу() {
        assertThat(YamlConfigurationService.environmentKey(ConfigFile.DATABASE, "mysql.password"))
                .isEqualTo("KOFAUTH_DATABASE_MYSQL_PASSWORD");
        // Дефис в пути тоже становится подчёркиванием.
        assertThat(YamlConfigurationService.environmentKey(ConfigFile.DATABASE, "mysql.pool.maximum-size"))
                .isEqualTo("KOFAUTH_DATABASE_MYSQL_POOL_MAXIMUM_SIZE");
        assertThat(YamlConfigurationService.environmentKey(ConfigFile.SECURITY, "jwt.secret"))
                .isEqualTo("KOFAUTH_SECURITY_JWT_SECRET");
    }

    @Test
    void пустая_переменная_окружения_игнорируется() {
        // Иначе KOFAUTH_..._PASSWORD="" затирал бы настроенный пароль пустотой.
        ConfigurationService config = service(Map.of("KOFAUTH_DATABASE_MYSQL_PASSWORD", ""));

        assertThat(config.getString(ConfigFile.DATABASE, "mysql.password", ""))
                .isEqualTo("из-файла");
    }

    @Test
    void числовое_и_логическое_значение_читаются_из_окружения() {
        ConfigurationService config = service(Map.of(
                "KOFAUTH_DATABASE_MYSQL_POOL_MAXIMUM_SIZE", "42",
                "KOFAUTH_DATABASE_REDIS_ENABLED", "false"));

        assertThat(config.getInt(ConfigFile.DATABASE, "mysql.pool.maximum-size", 0)).isEqualTo(42);
        assertThat(config.getBoolean(ConfigFile.DATABASE, "redis.enabled", true)).isFalse();
    }

    @Test
    void список_из_окружения_разбирается_по_запятой() {
        ConfigurationService config = service(Map.of(
                "KOFAUTH_VELOCITY_LIMBO_SERVERS", "limbo-1, limbo-2 ,limbo-3"));

        assertThat(config.getStringList(ConfigFile.VELOCITY, "limbo.servers"))
                .containsExactly("limbo-1", "limbo-2", "limbo-3");
    }

    @Test
    void файл_удалённый_после_запуска_не_ломает_перезагрузку() throws IOException {
        // Администратор может удалить ненужный конфиг — боту не нужен paper.yml.
        // Перезагрузка обязана это пережить, отдавая значения по умолчанию.
        ConfigurationService config = service(Map.of());
        Files.delete(configDir.resolve(ConfigFile.PAPER.fileName()));

        ConfigurationService.ReloadReport report = config.reload().join();

        assertThat(report.success()).isTrue();
        assertThat(config.contains(ConfigFile.PAPER, "mode")).isFalse();
        assertThat(config.getString(ConfigFile.PAPER, "mode", "BACKEND")).isEqualTo("BACKEND");
    }

    @Test
    void перезагрузка_подхватывает_изменения_файла() throws IOException {
        ConfigurationService config = service(Map.of());
        assertThat(config.getInt(ConfigFile.DATABASE, "mysql.pool.maximum-size", 0)).isEqualTo(10);

        write(ConfigFile.DATABASE, """
                mysql:
                  host: localhost
                  pool:
                    maximum-size: 25
                """);
        ConfigurationService.ReloadReport report = config.reload().join();

        assertThat(report.success()).isTrue();
        assertThat(config.getInt(ConfigFile.DATABASE, "mysql.pool.maximum-size", 0)).isEqualTo(25);
    }

    @Test
    void ошибка_в_файле_отменяет_перезагрузку_целиком() throws IOException {
        ConfigurationService config = service(Map.of());

        // Портим один файл, второй меняем корректно.
        write(ConfigFile.DATABASE, "mysql:\n  pool:\n    maximum-size: 99\n");
        write(ConfigFile.SECURITY, "password:\n  [не закрытая скобка\n");

        ConfigurationService.ReloadReport report = config.reload().join();

        assertThat(report.success()).isFalse();
        assertThat(report.errors()).isNotEmpty();
        // Прежняя конфигурация продолжает действовать целиком: значение из
        // корректно изменённого файла НЕ применилось.
        assertThat(config.getInt(ConfigFile.DATABASE, "mysql.pool.maximum-size", 0)).isEqualTo(10);
    }

    @Test
    void перезагружаемые_компоненты_вызываются_по_возрастанию_порядка() {
        ConfigurationService config = service(Map.of());
        List<String> order = new CopyOnWriteArrayList<>();

        config.registerReloadable(new RecordingReloadable("поздний", 200, order, false));
        config.registerReloadable(new RecordingReloadable("ранний", 10, order, false));
        config.registerReloadable(new RecordingReloadable("средний", 100, order, false));

        config.reload().join();

        // Пул соединений должен пересоздаваться раньше того, что на нём работает.
        assertThat(order).containsExactly("ранний", "средний", "поздний");
    }

    @Test
    void сбой_компонента_попадает_в_отчёт_но_не_останавливает_остальные() {
        ConfigurationService config = service(Map.of());
        List<String> order = new CopyOnWriteArrayList<>();

        config.registerReloadable(new RecordingReloadable("падающий", 10, order, true));
        config.registerReloadable(new RecordingReloadable("рабочий", 20, order, false));

        ConfigurationService.ReloadReport report = config.reload().join();

        assertThat(report.success()).isFalse();
        assertThat(report.errors()).containsKey("падающий");
        assertThat(order).contains("рабочий");
    }

    @Test
    void снятие_регистрации_исключает_компонент() {
        ConfigurationService config = service(Map.of());
        List<String> order = new CopyOnWriteArrayList<>();
        Reloadable component = new RecordingReloadable("компонент", 10, order, false);

        config.registerReloadable(component);
        config.unregisterReloadable(component);
        config.reload().join();

        assertThat(order).isEmpty();
    }

    @Test
    void initialize_создаёт_недостающие_файлы_из_шаблонов() {
        // config.yml не писался в setUp — должен появиться из ресурсов jar.
        service(Map.of());

        assertThat(configDir.resolve(ConfigFile.CONFIG.fileName())).exists();
        assertThat(configDir.resolve(ConfigFile.CAPTCHA.fileName())).exists();
    }

    @Test
    void initialize_не_перезаписывает_существующий_файл() throws IOException {
        service(Map.of());

        // Значение из setUp должно уцелеть, а не быть затёрто шаблоном.
        assertThat(Files.readString(configDir.resolve(ConfigFile.DATABASE.fileName())))
                .contains("из-файла");
    }

    /** Компонент, записывающий факт своей перезагрузки. */
    private record RecordingReloadable(String name,
                                       int order,
                                       List<String> log,
                                       boolean shouldFail) implements Reloadable {

        @Override
        public @NotNull CompletableFuture<Void> reload() {
            if (shouldFail) {
                return CompletableFuture.failedFuture(new IllegalStateException("не удалось"));
            }
            log.add(name);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NotNull String componentName() {
            return name;
        }

        @Override
        public int reloadOrder() {
            return order;
        }
    }
}
