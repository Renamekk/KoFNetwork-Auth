package net.kofnetwork.auth.core.config;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.config.Reloadable;
import net.kofnetwork.auth.api.exception.ConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Конфигурация на YAML с горячей перезагрузкой.
 *
 * <p><b>Атомарность.</b> Все документы лежат в одном {@link AtomicReference} на
 * неизменяемое отображение. Перезагрузка читает и разбирает все файлы во временную
 * структуру и лишь затем подменяет ссылку. Читатель поэтому всегда видит либо целиком
 * старую конфигурацию, либо целиком новую — но не смесь из наполовину применённых
 * файлов, которую невозможно ни воспроизвести, ни отладить.
 *
 * <p><b>Всё или ничего.</b> Ошибка в любом файле отменяет перезагрузку целиком.
 * Альтернатива — применить остальные — оставила бы сервер в состоянии, о котором
 * администратор не знает: он видит сообщение об ошибке в одном файле и не догадывается,
 * что восемь других уже подменены.
 *
 * <p><b>Приоритет источников:</b> переменная окружения → YAML → значение по умолчанию.
 * Имя переменной: {@code KOFAUTH_<ФАЙЛ>_<ПУТЬ>}, точки и дефисы заменяются
 * подчёркиванием. Например, {@code database.yml} + {@code mysql.password} →
 * {@code KOFAUTH_DATABASE_MYSQL_PASSWORD}.
 */
public final class YamlConfigurationService implements ConfigurationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(YamlConfigurationService.class);

    private static final String ENV_PREFIX = "KOFAUTH_";

    private final Path configDirectory;
    private final Executor ioExecutor;
    private final Map<String, String> environment;

    private final AtomicReference<Map<ConfigFile, YamlDocument>> documents =
            new AtomicReference<>(Map.of());

    private final List<Reloadable> reloadables = new CopyOnWriteArrayList<>();

    /**
     * @param configDirectory каталог с файлами конфигурации
     * @param ioExecutor      пул, на котором выполняется чтение файлов
     */
    public YamlConfigurationService(@NotNull Path configDirectory, @NotNull Executor ioExecutor) {
        this(configDirectory, ioExecutor, System.getenv());
    }

    /** Конструктор с явным окружением — для тестов. */
    YamlConfigurationService(@NotNull Path configDirectory,
                             @NotNull Executor ioExecutor,
                             @NotNull Map<String, String> environment) {
        this.configDirectory = configDirectory;
        this.ioExecutor = ioExecutor;
        this.environment = environment;
    }

    /**
     * Создаёт недостающие файлы из шаблонов в jar и выполняет первую загрузку.
     *
     * <p>Существующие файлы не перезаписываются: администратор их правил, и «обновление
     * до актуальной версии» стёрло бы настройки. Новые ключи, появившиеся в шаблоне,
     * подхватываются значениями по умолчанию из кода.
     *
     * @throws ConfigurationException если каталог не создаётся или файл не разбирается
     */
    public void initialize() {
        try {
            Files.createDirectories(configDirectory);
        } catch (IOException e) {
            throw new ConfigurationException("Не удалось создать каталог конфигурации " + configDirectory, e);
        }

        for (ConfigFile file : ConfigFile.values()) {
            Path target = configDirectory.resolve(file.fileName());
            if (Files.exists(target)) {
                continue;
            }
            copyDefault(file, target);
        }

        documents.set(readAll());
        LOGGER.info("Конфигурация загружена из {}", configDirectory.toAbsolutePath());
    }

    private void copyDefault(ConfigFile file, Path target) {
        String resource = "config/" + file.fileName();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                LOGGER.warn("В jar нет шаблона {} — файл {} не создан, будут использованы "
                        + "значения по умолчанию из кода", resource, file.fileName());
                return;
            }
            Files.copy(stream, target);
            LOGGER.info("Создан файл конфигурации {}", file.fileName());
        } catch (IOException e) {
            throw new ConfigurationException("Не удалось создать " + target, e);
        }
    }

    /** Читает и разбирает все файлы. Ничего не публикует — только возвращает результат. */
    private Map<ConfigFile, YamlDocument> readAll() {
        Map<ConfigFile, YamlDocument> loaded = new EnumMap<>(ConfigFile.class);
        for (ConfigFile file : ConfigFile.values()) {
            Path path = configDirectory.resolve(file.fileName());
            if (Files.exists(path)) {
                loaded.put(file, YamlDocument.load(path));
            } else {
                // Отсутствующий файл — не ошибка: модуль мог не создавать конфиг,
                // который ему не нужен (боту не нужен paper.yml).
                loaded.put(file, YamlDocument.EMPTY);
            }
        }
        return loaded;
    }

    private YamlDocument document(ConfigFile file) {
        return documents.get().getOrDefault(file, YamlDocument.EMPTY);
    }

    // ------------------------------------------------------------------ чтение значений

    /**
     * Имя переменной окружения для пары «файл + путь».
     *
     * <p>Открыт для тестов и для вывода в сообщении об ошибке: администратору, у
     * которого не подхватился пароль, полезно увидеть точное ожидаемое имя.
     */
    static @NotNull String environmentKey(@NotNull ConfigFile file, @NotNull String path) {
        String fileName = file.fileName().replace(".yml", "");
        return (ENV_PREFIX + fileName + "_" + path)
                .replace('.', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    private @Nullable String fromEnvironment(ConfigFile file, String path) {
        String value = environment.get(environmentKey(file, path));
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public @NotNull String getString(@NotNull ConfigFile file, @NotNull String path, @NotNull String defaultValue) {
        String value = getString(file, path);
        return value == null ? defaultValue : value;
    }

    @Override
    public @Nullable String getString(@NotNull ConfigFile file, @NotNull String path) {
        String fromEnv = fromEnvironment(file, path);
        return fromEnv != null ? fromEnv : document(file).getString(path);
    }

    @Override
    public int getInt(@NotNull ConfigFile file, @NotNull String path, int defaultValue) {
        String fromEnv = fromEnvironment(file, path);
        if (fromEnv != null) {
            return parseIntOrFail(fromEnv, file, path);
        }
        return document(file).getInt(path, defaultValue);
    }

    @Override
    public long getLong(@NotNull ConfigFile file, @NotNull String path, long defaultValue) {
        String fromEnv = fromEnvironment(file, path);
        if (fromEnv != null) {
            try {
                return Long.parseLong(fromEnv.trim());
            } catch (NumberFormatException e) {
                throw new ConfigurationException(environmentFailure(file, path, fromEnv, "целым числом"));
            }
        }
        return document(file).getLong(path, defaultValue);
    }

    @Override
    public double getDouble(@NotNull ConfigFile file, @NotNull String path, double defaultValue) {
        String fromEnv = fromEnvironment(file, path);
        if (fromEnv != null) {
            try {
                return Double.parseDouble(fromEnv.trim());
            } catch (NumberFormatException e) {
                throw new ConfigurationException(environmentFailure(file, path, fromEnv, "числом"));
            }
        }
        return document(file).getDouble(path, defaultValue);
    }

    @Override
    public boolean getBoolean(@NotNull ConfigFile file, @NotNull String path, boolean defaultValue) {
        String fromEnv = fromEnvironment(file, path);
        if (fromEnv != null) {
            String normalized = fromEnv.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
            throw new ConfigurationException(environmentFailure(file, path, fromEnv, "true или false"));
        }
        return document(file).getBoolean(path, defaultValue);
    }

    @Override
    public @NotNull Duration getDuration(@NotNull ConfigFile file,
                                         @NotNull String path,
                                         @NotNull Duration defaultValue) {
        String fromEnv = fromEnvironment(file, path);
        if (fromEnv != null) {
            return YamlDocument.parseDuration(fromEnv.trim(), environmentKey(file, path));
        }
        return document(file).getDuration(path, defaultValue);
    }

    @Override
    public @NotNull List<String> getStringList(@NotNull ConfigFile file, @NotNull String path) {
        String fromEnv = fromEnvironment(file, path);
        if (fromEnv != null) {
            // В окружении список задаётся через запятую: массивы там представить нечем.
            return List.of(fromEnv.split("\\s*,\\s*"));
        }
        return document(file).getStringList(path);
    }

    @Override
    public @NotNull Map<String, Object> getSection(@NotNull ConfigFile file, @NotNull String path) {
        return document(file).getSection(path);
    }

    @Override
    public boolean contains(@NotNull ConfigFile file, @NotNull String path) {
        return fromEnvironment(file, path) != null || document(file).contains(path);
    }

    private int parseIntOrFail(String raw, ConfigFile file, String path) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigurationException(environmentFailure(file, path, raw, "целым числом"));
        }
    }

    private String environmentFailure(ConfigFile file, String path, String raw, String expected) {
        return "Переменная окружения " + environmentKey(file, path) + " должна быть " + expected
                + ", получено '" + raw + "'";
    }

    // ------------------------------------------------------------------ перезагрузка

    @Override
    public @NotNull CompletableFuture<ReloadReport> reload() {
        return CompletableFuture.supplyAsync(this::reloadBlocking, ioExecutor);
    }

    private ReloadReport reloadBlocking() {
        long startedAt = System.nanoTime();
        Map<String, String> errors = new LinkedHashMap<>();

        Map<ConfigFile, YamlDocument> fresh;
        try {
            fresh = readAll();
        } catch (ConfigurationException e) {
            errors.put("configuration", e.getMessage());
            LOGGER.error("Перезагрузка отменена, продолжает работать прежняя конфигурация", e);
            return new ReloadReport(false, List.of(), errors, elapsedMs(startedAt));
        }

        // Разбор прошёл — публикуем целиком.
        documents.set(fresh);

        List<String> reloadedFiles = new ArrayList<>();
        for (ConfigFile file : ConfigFile.values()) {
            reloadedFiles.add(file.fileName());
        }

        // Компоненты пересоздаются по возрастанию reloadOrder: пул соединений раньше
        // того, что на нём работает.
        List<Reloadable> ordered = new ArrayList<>(reloadables);
        ordered.sort(Comparator.comparingInt(Reloadable::reloadOrder));

        for (Reloadable reloadable : ordered) {
            try {
                reloadable.reload().join();
            } catch (RuntimeException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.put(reloadable.componentName(), String.valueOf(cause.getMessage()));
                LOGGER.error("Не удалось перезагрузить компонент {}", reloadable.componentName(), cause);
            }
        }

        boolean success = errors.isEmpty();
        long durationMs = elapsedMs(startedAt);
        if (success) {
            LOGGER.info("Конфигурация перезагружена за {} мс", durationMs);
        } else {
            LOGGER.warn("Конфигурация перезагружена с ошибками в {} компонентах за {} мс",
                    errors.size(), durationMs);
        }
        return new ReloadReport(success, reloadedFiles, errors, durationMs);
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    @Override
    public void registerReloadable(@NotNull Reloadable reloadable) {
        reloadables.add(reloadable);
    }

    @Override
    public void unregisterReloadable(@NotNull Reloadable reloadable) {
        reloadables.remove(reloadable);
    }

    @Override
    public @NotNull Path configDirectory() {
        return configDirectory;
    }
}
