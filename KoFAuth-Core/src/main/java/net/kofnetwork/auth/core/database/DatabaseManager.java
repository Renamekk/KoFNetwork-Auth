package net.kofnetwork.auth.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.config.Reloadable;
import net.kofnetwork.auth.api.exception.ConfigurationException;
import net.kofnetwork.auth.api.exception.RepositoryException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Владелец пула соединений и миграций схемы.
 *
 * <p><b>Порядок перезагрузки.</b> {@link #reload()} создаёт <em>новый</em> пул,
 * проверяет его рабочим соединением и только затем подменяет ссылку и закрывает
 * старый. Обратный порядок — «закрыть старый, открыть новый» — означал бы, что
 * опечатка в пароле базы оставляет сеть без аутентификации до перезапуска.
 *
 * <p><b>Часовые пояса.</b> В строку подключения принудительно добавляются
 * {@code connectionTimeZone=UTC} и {@code preserveInstants=true}. Без них драйвер
 * пересчитывает {@code DATETIME} в локальный пояс приложения, и время входа
 * «уезжает» на серверах в разных зонах — ошибка, которая обнаруживается только
 * при разборе инцидента полгода спустя.
 */
public final class DatabaseManager implements Reloadable, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseManager.class);

    /** Раньше репозиториев и кэша: они работают поверх этого пула. */
    private static final int RELOAD_ORDER = 10;

    private final ConfigurationService config;
    private final Executor ioExecutor;
    private final AtomicReference<HikariDataSource> dataSource = new AtomicReference<>();

    public DatabaseManager(@NotNull ConfigurationService config, @NotNull Executor ioExecutor) {
        this.config = config;
        this.ioExecutor = ioExecutor;
    }

    /**
     * Поднимает пул и применяет миграции.
     *
     * @throws ConfigurationException если не удалось подключиться или мигрировать
     */
    public void start() {
        HikariDataSource pool = buildDataSource();
        verify(pool);
        dataSource.set(pool);

        if (config.getBoolean(ConfigFile.DATABASE, "mysql.migrate-on-startup", true)) {
            migrate(pool);
        }
        LOGGER.info("Пул соединений с MySQL поднят: {} соединений максимум",
                pool.getMaximumPoolSize());
    }

    private HikariDataSource buildDataSource() {
        String host = config.getString(ConfigFile.DATABASE, "mysql.host", "localhost");
        int port = config.getInt(ConfigFile.DATABASE, "mysql.port", 3306);
        String database = config.getString(ConfigFile.DATABASE, "mysql.database", "kofauth");
        String username = config.getString(ConfigFile.DATABASE, "mysql.username", "kofauth");
        String password = config.getString(ConfigFile.DATABASE, "mysql.password", "");

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("kofauth-hikari");
        hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // Свойства из конфигурации.
        Map<String, Object> properties = config.getSection(ConfigFile.DATABASE, "mysql.properties");
        properties.forEach((key, value) -> hikari.addDataSourceProperty(key, String.valueOf(value)));

        // Обязательные свойства выставляются после пользовательских: их отключение
        // ломает трактовку времени, и разрешать это администратору незачем.
        hikari.addDataSourceProperty("connectionTimeZone", "UTC");
        hikari.addDataSourceProperty("preserveInstants", "true");

        hikari.setMaximumPoolSize(config.getInt(ConfigFile.DATABASE, "mysql.pool.maximum-size", 10));
        hikari.setMinimumIdle(config.getInt(ConfigFile.DATABASE, "mysql.pool.minimum-idle", 2));
        hikari.setConnectionTimeout(millis(ConfigFile.DATABASE, "mysql.pool.connection-timeout",
                Duration.ofSeconds(10)));
        hikari.setIdleTimeout(millis(ConfigFile.DATABASE, "mysql.pool.idle-timeout",
                Duration.ofMinutes(10)));
        hikari.setMaxLifetime(millis(ConfigFile.DATABASE, "mysql.pool.max-lifetime",
                Duration.ofMinutes(30)));
        hikari.setLeakDetectionThreshold(millis(ConfigFile.DATABASE,
                "mysql.pool.leak-detection-threshold", Duration.ofSeconds(30)));

        // Автокоммит включён: транзакции открываются явно там, где они нужны.
        hikari.setAutoCommit(true);

        try {
            return new HikariDataSource(hikari);
        } catch (RuntimeException e) {
            throw new ConfigurationException(
                    "Не удалось создать пул соединений с MySQL " + host + ":" + port + "/" + database
                            + ". Проверьте адрес, учётные данные и доступность сервера.", e);
        }
    }

    private long millis(ConfigFile file, String path, Duration fallback) {
        return config.getDuration(file, path, fallback).toMillis();
    }

    /** Берёт соединение из пула и возвращает — проверка, что база действительно отвечает. */
    private void verify(HikariDataSource pool) {
        try (Connection connection = pool.getConnection()) {
            if (!connection.isValid(5)) {
                throw new ConfigurationException("Соединение с MySQL получено, но нерабочее");
            }
        } catch (SQLException e) {
            pool.close();
            throw new ConfigurationException(
                    "Не удалось подключиться к MySQL: " + e.getMessage(), e);
        }
    }

    private void migrate(DataSource source) {
        try {
            MigrateResult result = Flyway.configure(getClass().getClassLoader())
                    .dataSource(source)
                    .locations("classpath:db/migration")
                    // Схема уже могла существовать до подключения Flyway.
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    // Правка применённой миграции — ошибка разработчика, а не повод
                    // молча продолжить: расхождение контрольной суммы должно быть заметным.
                    .validateOnMigrate(true)
                    .load()
                    .migrate();

            if (result.migrationsExecuted > 0) {
                LOGGER.info("Применено миграций: {} (схема на версии {})",
                        result.migrationsExecuted, result.targetSchemaVersion);
            } else {
                LOGGER.info("Схема актуальна, миграции не требуются");
            }
        } catch (RuntimeException e) {
            throw new ConfigurationException(
                    "Не удалось применить миграции схемы. Если база правилась вручную, "
                            + "сверьте таблицу flyway_schema_history.", e);
        }
    }

    /**
     * Источник данных.
     *
     * @throws RepositoryException если пул ещё не поднят или уже закрыт
     */
    public @NotNull DataSource dataSource() {
        HikariDataSource pool = dataSource.get();
        if (pool == null || pool.isClosed()) {
            throw new RepositoryException("Пул соединений с MySQL недоступен");
        }
        return pool;
    }

    /**
     * Соединение из пула. Вызывающий обязан закрыть его — иначе оно не вернётся в пул,
     * и через {@code leak-detection-threshold} в логе появится предупреждение.
     */
    public @NotNull Connection connection() throws SQLException {
        return dataSource().getConnection();
    }

    /** Доступна ли база прямо сейчас. */
    public boolean isHealthy() {
        HikariDataSource pool = dataSource.get();
        if (pool == null || pool.isClosed()) {
            return false;
        }
        try (Connection connection = pool.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public @NotNull CompletableFuture<Void> reload() {
        return CompletableFuture.runAsync(() -> {
            HikariDataSource fresh = buildDataSource();
            try {
                verify(fresh);
            } catch (RuntimeException e) {
                // Новый пул нерабочий — старый продолжает обслуживать сеть.
                fresh.close();
                throw e;
            }
            HikariDataSource previous = dataSource.getAndSet(fresh);
            if (previous != null) {
                // Закрываем мягко: soft-eviction дожидается возврата занятых соединений.
                previous.close();
            }
            LOGGER.info("Пул соединений пересоздан");
        }, ioExecutor);
    }

    @Override
    public @NotNull String componentName() {
        return "DatabaseManager";
    }

    @Override
    public int reloadOrder() {
        return RELOAD_ORDER;
    }

    @Override
    public void close() {
        HikariDataSource pool = dataSource.getAndSet(null);
        if (pool != null && !pool.isClosed()) {
            pool.close();
            LOGGER.info("Пул соединений с MySQL закрыт");
        }
    }

    /** Снимок состояния пула для {@code /auth info} и метрик. */
    public @NotNull PoolSnapshot snapshot() {
        HikariDataSource pool = dataSource.get();
        if (pool == null || pool.isClosed()) {
            return new PoolSnapshot(false, 0, 0, 0, 0);
        }
        var mx = pool.getHikariPoolMXBean();
        return new PoolSnapshot(true,
                mx.getTotalConnections(),
                mx.getActiveConnections(),
                mx.getIdleConnections(),
                mx.getThreadsAwaitingConnection());
    }

    /** Состояние пула соединений. */
    public record PoolSnapshot(boolean up,
                               int total,
                               int active,
                               int idle,
                               int awaiting) {
    }
}
