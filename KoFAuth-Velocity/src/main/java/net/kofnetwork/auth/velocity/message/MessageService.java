package net.kofnetwork.auth.velocity.message;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Разбор сообщений из конфигурации в компоненты Adventure.
 *
 * <p>Используется MiniMessage, а не устаревшие коды {@code §}: он поддерживает
 * градиенты, ховеры и кликабельные элементы, а главное — не ломается, когда
 * администратор забыл символ секции.
 *
 * <p>Компоненты не кэшируются: строки читаются из {@link ConfigurationService},
 * который умеет горячую перезагрузку, и закэшированный компонент пережил бы
 * {@code /auth reload}, оставив на экране старый текст.
 */
public final class MessageService {

    private final ConfigurationService config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MessageService(@NotNull ConfigurationService config) {
        this.config = config;
    }

    /** Сообщение по пути в {@code config.yml} с префиксом сети. */
    public @NotNull Component prefixed(@NotNull String path, @NotNull String fallback) {
        String prefix = config.getString(ConfigFile.CONFIG, "messages.prefix", "");
        return miniMessage.deserialize(prefix + raw(path, fallback));
    }

    /** Сообщение без префикса — для заголовков и кик-сообщений. */
    public @NotNull Component plain(@NotNull String path, @NotNull String fallback) {
        return miniMessage.deserialize(raw(path, fallback));
    }

    /** Сообщение с подстановками вида {@code <player>}. */
    public @NotNull Component prefixed(@NotNull String path,
                                       @NotNull String fallback,
                                       @NotNull Map<String, String> placeholders) {
        String prefix = config.getString(ConfigFile.CONFIG, "messages.prefix", "");
        return miniMessage.deserialize(substitute(prefix + raw(path, fallback), placeholders));
    }

    /** Кик-сообщение из {@code velocity.yml}. */
    public @NotNull Component kick(@NotNull String path, @NotNull String fallback) {
        return miniMessage.deserialize(
                config.getString(ConfigFile.VELOCITY, "kick-messages." + path, fallback));
    }

    /** Произвольная строка MiniMessage. */
    public @NotNull Component parse(@NotNull String value) {
        return miniMessage.deserialize(value);
    }

    private String raw(String path, String fallback) {
        return config.getString(ConfigFile.CONFIG, "messages." + path, fallback);
    }

    /**
     * Подставляет значения.
     *
     * <p>Подстановка выполняется <em>до</em> разбора MiniMessage, поэтому значение,
     * содержащее теги, будет ими интерпретировано. Сюда попадают только наши
     * собственные данные (ник, число попыток, срок); пользовательский ввод
     * подставлять этим методом нельзя.
     */
    private static String substitute(String template, Map<String, String> placeholders) {
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        return result;
    }
}
