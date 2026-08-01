package net.kofnetwork.auth.api.repository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ к таблице {@code settings} — настройкам, изменяемым без перезапуска.
 *
 * <p>Разделение с YAML такое: YAML описывает то, что нужно знать процессу <em>до</em>
 * подключения к базе (адрес MySQL, ключ шифрования, токены ботов), а {@code settings} —
 * то, что администратор меняет на ходу из панели и что должно примениться сразу на всех
 * узлах сети. Положить адрес базы в базу нельзя; держать «включена ли регистрация» в
 * файле на десяти серверах — неудобно.
 */
public interface SettingsRepository {

    @NotNull CompletableFuture<Optional<String>> get(@NotNull String key);

    /** Все настройки одной выборкой — для первичного прогрева кэша. */
    @NotNull CompletableFuture<Map<String, String>> getAll();

    /**
     * Записывает значение.
     *
     * @param updatedBy кто изменил; {@code null} для системного изменения
     */
    @NotNull CompletableFuture<Void> set(@NotNull String key,
                                         @Nullable String value,
                                         @Nullable Long updatedBy);

    @NotNull CompletableFuture<Boolean> delete(@NotNull String key);
}
