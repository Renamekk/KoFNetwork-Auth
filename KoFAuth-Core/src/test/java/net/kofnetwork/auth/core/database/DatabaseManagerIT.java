package net.kofnetwork.auth.core.database;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.core.concurrent.AsyncExecutors;
import net.kofnetwork.auth.core.config.YamlConfigurationService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверка пула соединений и миграций на настоящем MySQL.
 *
 * <p>Юнит-тестами это не покрывается принципиально: смысл {@link DatabaseManager} —
 * в поведении при отказе базы и в том, что Flyway разворачивает схему целиком.
 * Мок JDBC подтвердил бы только, что мы правильно вызываем моки.
 */
@Testcontainers
class DatabaseManagerIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kofauth")
            .withUsername("kofauth")
            .withPassword("kofauth-secret");

    @TempDir
    Path configDir;

    private AsyncExecutors executors;
    private DatabaseManager database;

    @BeforeEach
    void setUp() throws IOException {
        executors = new AsyncExecutors(4);
        database = new DatabaseManager(config(MYSQL.getFirstMappedPort()), executors.io());
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
        if (executors != null) {
            executors.close();
        }
    }

    @AfterAll
    static void reportImage() {
        // Полезно в отчёте CI: на какой именно версии MySQL прошли тесты.
        System.out.println("Проверено на " + MYSQL.getDockerImageName());
    }

    private YamlConfigurationService config(int port) throws IOException {
        Files.writeString(configDir.resolve(ConfigFile.DATABASE.fileName()), """
                mysql:
                  host: %s
                  port: %d
                  database: kofauth
                  username: kofauth
                  password: kofauth-secret
                  properties:
                    useSSL: false
                    allowPublicKeyRetrieval: true
                  pool:
                    maximum-size: 4
                    minimum-idle: 1
                    connection-timeout: 10s
                  migrate-on-startup: true
                """.formatted(MYSQL.getHost(), port), StandardCharsets.UTF_8);

        YamlConfigurationService service =
                new YamlConfigurationService(configDir, Runnable::run);
        service.initialize();
        return service;
    }

    @Test
    void поднимает_пул_и_применяет_миграции() throws Exception {
        database.start();

        assertThat(database.isHealthy()).isTrue();

        List<String> tables = new ArrayList<>();
        try (Connection connection = database.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW TABLES")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }

        // 17 таблиц схемы плюс журнал Flyway.
        assertThat(tables).contains("users", "sessions", "devices", "tokens", "emails",
                "telegram", "discord", "totp", "captcha", "security_logs", "login_history",
                "roles", "permissions", "role_permissions", "user_roles", "servers", "settings",
                "flyway_schema_history");
    }

    @Test
    void миграции_наполняют_справочники() throws Exception {
        database.start();

        try (Connection connection = database.connection();
             Statement statement = connection.createStatement()) {
            assertThat(scalar(statement, "SELECT COUNT(*) FROM roles")).isEqualTo(7);
            assertThat(scalar(statement, "SELECT COUNT(*) FROM permissions")).isEqualTo(24);
            assertThat(scalar(statement, "SELECT COUNT(*) FROM role_permissions")).isEqualTo(60);
            assertThat(scalar(statement, "SELECT COUNT(*) FROM settings")).isEqualTo(17);
        }
    }

    @Test
    void повторный_запуск_не_применяет_миграции_заново() throws Exception {
        database.start();
        int firstRun;
        try (Connection connection = database.connection();
             Statement statement = connection.createStatement()) {
            firstRun = scalar(statement, "SELECT COUNT(*) FROM flyway_schema_history");
        }
        database.close();

        DatabaseManager second = new DatabaseManager(config(MYSQL.getFirstMappedPort()), executors.io());
        try {
            second.start();
            try (Connection connection = second.connection();
                 Statement statement = connection.createStatement()) {
                assertThat(scalar(statement, "SELECT COUNT(*) FROM flyway_schema_history"))
                        .isEqualTo(firstRun);
            }
        } finally {
            second.close();
        }
    }

    @Test
    void соединение_работает_в_UTC() throws Exception {
        // Без connectionTimeZone=UTC драйвер пересчитывает DATETIME в локальный пояс,
        // и время входа «уезжает» на серверах в разных зонах.
        database.start();

        try (Connection connection = database.connection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())")) {
            assertThat(rs.next()).isTrue();
            // Сессия соединения должна считать «сейчас» тем же, что и UTC.
            assertThat(Math.abs(rs.getLong(1))).isLessThanOrEqualTo(1L);
        }
    }

    @Test
    void снимок_пула_отражает_состояние() {
        assertThat(database.snapshot().up()).isFalse();

        database.start();

        DatabaseManager.PoolSnapshot snapshot = database.snapshot();
        assertThat(snapshot.up()).isTrue();
        assertThat(snapshot.total()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void перезагрузка_пересоздаёт_пул() throws Exception {
        database.start();

        database.reload().join();

        assertThat(database.isHealthy()).isTrue();
        try (Connection connection = database.connection()) {
            assertThat(connection.isValid(2)).isTrue();
        }
    }

    @Test
    void неудачная_перезагрузка_оставляет_рабочим_прежний_пул() throws Exception {
        database.start();

        // Подсовываем конфигурацию с неверным портом.
        Files.writeString(configDir.resolve(ConfigFile.DATABASE.fileName()), """
                mysql:
                  host: %s
                  port: 1
                  database: kofauth
                  username: kofauth
                  password: kofauth-secret
                  pool:
                    connection-timeout: 2s
                """.formatted(MYSQL.getHost()), StandardCharsets.UTF_8);
        YamlConfigurationService reloaded =
                new YamlConfigurationService(configDir, Runnable::run);
        reloaded.initialize();
        DatabaseManager broken = new DatabaseManager(reloaded, executors.io());

        // Новый пул не поднимется...
        assertThatThrownBy(() -> broken.start()).isInstanceOf(RuntimeException.class);

        // ...а прежний продолжает обслуживать сеть.
        assertThat(database.isHealthy()).isTrue();
    }

    @Test
    void обращение_к_закрытому_пулу_даёт_понятную_ошибку() {
        database.start();
        database.close();

        assertThatThrownBy(() -> database.dataSource())
                .isInstanceOf(net.kofnetwork.auth.api.exception.RepositoryException.class)
                .hasMessageContaining("недоступен");
        assertThat(database.isHealthy()).isFalse();
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
