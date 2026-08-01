package net.kofnetwork.auth.api.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ к конфигурации YAML с поддержкой горячей перезагрузки.
 *
 * <p><b>Приоритет источников:</b> переменная окружения → значение из YAML → значение
 * по умолчанию. Переменные окружения выше, потому что в контейнерном развёртывании
 * секреты приходят именно оттуда, и файл в образе не должен их переопределять.
 * Имя переменной строится как {@code KOFAUTH_<ФАЙЛ>_<ПУТЬ>} с заменой точек и дефисов
 * на подчёркивания: {@code database.yml} + {@code mysql.password} →
 * {@code KOFAUTH_DATABASE_MYSQL_PASSWORD}.
 *
 * <p><b>Потокобезопасность.</b> Чтение возможно из любого потока в любой момент, в том
 * числе во время перезагрузки: значения публикуются атомарной подменой снимка, поэтому
 * читатель видит либо целиком старую конфигурацию, либо целиком новую, но никогда
 * наполовину применённую.
 */
public interface ConfigurationService {

    @NotNull String getString(@NotNull ConfigFile file, @NotNull String path, @NotNull String defaultValue);

    @Nullable String getString(@NotNull ConfigFile file, @NotNull String path);

    int getInt(@NotNull ConfigFile file, @NotNull String path, int defaultValue);

    long getLong(@NotNull ConfigFile file, @NotNull String path, long defaultValue);

    double getDouble(@NotNull ConfigFile file, @NotNull String path, double defaultValue);

    boolean getBoolean(@NotNull ConfigFile file, @NotNull String path, boolean defaultValue);

    /**
     * Читает длительность из человекочитаемой записи: {@code 30s}, {@code 15m},
     * {@code 24h}, {@code 7d}.
     *
     * <p>Единицы измерения в самом значении, а не в имени ключа: ключ
     * {@code session.ttl-minutes} со значением {@code 1440} требует от читающего
     * пересчёта в уме и провоцирует ошибку на порядок при смене единиц.
     */
    @NotNull Duration getDuration(@NotNull ConfigFile file, @NotNull String path, @NotNull Duration defaultValue);

    @NotNull List<String> getStringList(@NotNull ConfigFile file, @NotNull String path);

    /** Вложенная секция как отображение. Пустое отображение, если секции нет. */
    @NotNull Map<String, Object> getSection(@NotNull ConfigFile file, @NotNull String path);

    /** Есть ли значение по указанному пути. */
    boolean contains(@NotNull ConfigFile file, @NotNull String path);

    /**
     * Перезагружает все файлы и оповещает зарегистрированные {@link Reloadable}.
     *
     * <p>Сначала читаются и разбираются все файлы, и лишь затем применяются: ошибка
     * в одном файле отменяет перезагрузку целиком, оставляя работать прежнюю
     * конфигурацию. Иначе сервер оказался бы с половиной новых и половиной старых
     * настроек — состояние, которое невозможно ни воспроизвести, ни отладить.
     *
     * @return отчёт о перезагрузке
     */
    @NotNull CompletableFuture<ReloadReport> reload();

    /**
     * Отчёт о перезагрузке.
     *
     * @param success       удалось ли применить новую конфигурацию
     * @param reloadedFiles какие файлы перечитаны
     * @param errors        сообщения об ошибках по файлам
     * @param durationMs    сколько заняло
     */
    record ReloadReport(boolean success,
                        @NotNull List<String> reloadedFiles,
                        @NotNull Map<String, String> errors,
                        long durationMs) {

        public ReloadReport {
            reloadedFiles = reloadedFiles == null ? List.of() : List.copyOf(reloadedFiles);
            errors = errors == null ? Map.of() : Map.copyOf(errors);
        }
    }

    /**
     * Регистрирует компонент, которому нужен реальный пересоздаваемый ресурс.
     *
     * @see Reloadable
     */
    void registerReloadable(@NotNull Reloadable reloadable);

    /** Снимает регистрацию. Обязателен при остановке модуля, иначе утечёт ссылка. */
    void unregisterReloadable(@NotNull Reloadable reloadable);

    /** Каталог, из которого читаются файлы конфигурации. */
    @NotNull java.nio.file.Path configDirectory();
}
