package net.kofnetwork.auth.core.config;

import net.kofnetwork.auth.api.exception.ConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Разобранный YAML-документ с доступом по точечному пути.
 *
 * <p>Неизменяем: после разбора содержимое не меняется, а перезагрузка создаёт новый
 * экземпляр. Благодаря этому читатель, взявший документ, работает с согласованным
 * снимком, даже если в этот момент идёт {@code /auth reload}.
 *
 * <p><b>Только {@link SafeConstructor}.</b> SnakeYAML по умолчанию умеет создавать
 * произвольные Java-объекты по тегу {@code !!}, и разбор недоверенного YAML этим
 * конструктором — известный путь к удалённому выполнению кода. Файлы конфигурации
 * пишет администратор, но безопасный разбор здесь ничего не стоит.
 */
public final class YamlDocument {

    /** Пустой документ: используется, когда файл отсутствует. */
    public static final YamlDocument EMPTY = new YamlDocument(Map.of());

    private final Map<String, Object> root;

    private YamlDocument(Map<String, Object> root) {
        this.root = root;
    }

    /**
     * Разбирает YAML из файла.
     *
     * @throws ConfigurationException при синтаксической ошибке или проблеме чтения
     */
    public static @NotNull YamlDocument load(@NotNull Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader, file.toString());
        } catch (IOException e) {
            throw new ConfigurationException("Не удалось прочитать " + file, e);
        }
    }

    /** Разбирает YAML из потока — используется для файлов по умолчанию из jar. */
    public static @NotNull YamlDocument load(@NotNull InputStream stream, @NotNull String source) {
        try (Reader reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return parse(reader, source);
        } catch (IOException e) {
            throw new ConfigurationException("Не удалось прочитать " + source, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static YamlDocument parse(Reader reader, String source) {
        LoaderOptions options = new LoaderOptions();
        // Защита от «YAML-бомбы»: документа, раскрывающегося в гигабайты через якоря.
        options.setMaxAliasesForCollections(64);
        options.setAllowDuplicateKeys(false);

        try {
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            if (loaded == null) {
                return EMPTY;
            }
            if (!(loaded instanceof Map)) {
                throw new ConfigurationException(
                        "Корень " + source + " должен быть отображением ключ-значение, получено "
                                + loaded.getClass().getSimpleName());
            }
            return new YamlDocument((Map<String, Object>) loaded);
        } catch (YAMLException e) {
            throw new ConfigurationException("Синтаксическая ошибка в " + source + ": " + e.getMessage(), e);
        }
    }

    /**
     * Значение по точечному пути, например {@code mysql.pool.maximum-size}.
     *
     * @return значение или {@code null}, если путь не найден
     */
    public @Nullable Object get(@NotNull String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    public boolean contains(@NotNull String path) {
        return get(path) != null;
    }

    public @Nullable String getString(@NotNull String path) {
        Object value = get(path);
        return value == null ? null : String.valueOf(value);
    }

    public @NotNull String getString(@NotNull String path, @NotNull String defaultValue) {
        String value = getString(path);
        return value == null ? defaultValue : value;
    }

    public int getInt(@NotNull String path, int defaultValue) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new ConfigurationException(
                        "Значение " + path + " должно быть целым числом, получено '" + s + "'");
            }
        }
        return defaultValue;
    }

    public long getLong(@NotNull String path, long defaultValue) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new ConfigurationException(
                        "Значение " + path + " должно быть целым числом, получено '" + s + "'");
            }
        }
        return defaultValue;
    }

    public double getDouble(@NotNull String path, double defaultValue) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new ConfigurationException(
                        "Значение " + path + " должно быть числом, получено '" + s + "'");
            }
        }
        return defaultValue;
    }

    /**
     * Логическое значение.
     *
     * <p>Строка распознаётся строго: принимаются только {@code true} и {@code false}.
     * Трактовать любое непустое значение как истину нельзя — опечатка {@code flase}
     * тогда молча включила бы отключаемую защиту.
     */
    public boolean getBoolean(@NotNull String path, boolean defaultValue) {
        Object value = get(path);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String s) {
            String normalized = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
            throw new ConfigurationException(
                    "Значение " + path + " должно быть true или false, получено '" + s + "'");
        }
        return defaultValue;
    }

    /**
     * Длительность в человекочитаемой записи: {@code 30s}, {@code 15m}, {@code 24h}, {@code 7d}.
     *
     * <p>Число без суффикса трактуется как секунды.
     */
    public @NotNull Duration getDuration(@NotNull String path, @NotNull Duration defaultValue) {
        Object value = get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return Duration.ofSeconds(number.longValue());
        }
        return parseDuration(String.valueOf(value).trim(), path);
    }

    /** Разбирает запись длительности. Вынесено отдельно ради тестируемости. */
    static @NotNull Duration parseDuration(@NotNull String raw, @NotNull String path) {
        if (raw.isEmpty()) {
            throw new ConfigurationException("Пустое значение длительности в " + path);
        }
        char suffix = raw.charAt(raw.length() - 1);
        String digits = Character.isDigit(suffix) ? raw : raw.substring(0, raw.length() - 1);

        long amount;
        try {
            amount = Long.parseLong(digits.trim());
        } catch (NumberFormatException e) {
            throw new ConfigurationException(
                    "Некорректная длительность в " + path + ": '" + raw
                            + "'. Ожидается формат 30s, 15m, 24h или 7d.");
        }
        if (amount < 0) {
            throw new ConfigurationException("Длительность в " + path + " не может быть отрицательной");
        }
        return switch (Character.toLowerCase(suffix)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> {
                if (Character.isDigit(suffix)) {
                    yield Duration.ofSeconds(amount);
                }
                throw new ConfigurationException(
                        "Неизвестная единица длительности '" + suffix + "' в " + path
                                + ". Допустимы s (секунды), m (минуты), h (часы), d (дни).");
            }
        };
    }

    /** Список строк. Пустой список, если путь не найден. */
    public @NotNull List<String> getStringList(@NotNull String path) {
        Object value = get(path);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return Collections.unmodifiableList(result);
        }
        if (value instanceof String s) {
            // Одиночное значение вместо списка — частая опечатка; принимаем как список из одного.
            return List.of(s);
        }
        return List.of();
    }

    /** Вложенная секция. Пустое отображение, если путь не найден или это не секция. */
    @SuppressWarnings("unchecked")
    public @NotNull Map<String, Object> getSection(@NotNull String path) {
        Object value = get(path);
        return value instanceof Map<?, ?> map
                ? Collections.unmodifiableMap((Map<String, Object>) map)
                : Map.of();
    }

    /** Пуст ли документ. */
    public boolean isEmpty() {
        return root.isEmpty();
    }
}
