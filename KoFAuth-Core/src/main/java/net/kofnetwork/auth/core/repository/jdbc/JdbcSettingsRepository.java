package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.repository.SettingsRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link SettingsRepository} на JDBC. */
public final class JdbcSettingsRepository implements SettingsRepository {

    private final SqlExecutor sql;

    public JdbcSettingsRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> get(@NotNull String key) {
        return sql.queryOne("SELECT setting_value FROM settings WHERE setting_key = ?",
                rs -> rs.getString("setting_value"), key);
    }

    @Override
    public @NotNull CompletableFuture<Map<String, String>> getAll() {
        return sql.queryList("SELECT setting_key, setting_value FROM settings",
                rs -> Map.entry(rs.getString("setting_key"),
                        // NULL допустим по схеме, но Map.entry его не принимает.
                        Optional.ofNullable(rs.getString("setting_value")).orElse("")),
                new Object[0]
        ).thenApply(entries -> {
            Map<String, String> result = new LinkedHashMap<>();
            entries.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
            return result;
        });
    }

    @Override
    public @NotNull CompletableFuture<Void> set(@NotNull String key,
                                                @Nullable String value,
                                                @Nullable Long updatedBy) {
        // Настройка могла не существовать: панель администратора вправе задать
        // ключ, которого не было в стартовых данных.
        return sql.update("""
                        INSERT INTO settings (setting_key, setting_value, updated_by)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value),
                                                updated_by = VALUES(updated_by)
                        """,
                key, value, updatedBy
        ).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull String key) {
        // editable = 0 защищает служебные ключи вроде schema.version от удаления
        // из панели администратора.
        return sql.update("DELETE FROM settings WHERE setting_key = ? AND editable = 1", key)
                .thenApply(affected -> affected > 0);
    }
}
