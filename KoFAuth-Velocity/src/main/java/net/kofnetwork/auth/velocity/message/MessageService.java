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

    /** Сообщение по пути в {@code messages.yml} с префиксом сети. */
    public @NotNull Component prefixed(@NotNull String path, @NotNull String fallback) {
        return miniMessage.deserialize(prefix() + raw(path, fallback));
    }

    /** Сообщение без префикса — для заголовков и надписей на экране. */
    public @NotNull Component plain(@NotNull String path, @NotNull String fallback) {
        return miniMessage.deserialize(raw(path, fallback));
    }

    /** Сообщение с подстановками вида {@code <player>}. */
    public @NotNull Component prefixed(@NotNull String path,
                                       @NotNull String fallback,
                                       @NotNull Map<String, String> placeholders) {
        return miniMessage.deserialize(substitute(prefix() + raw(path, fallback), placeholders));
    }

    /**
     * Кик-сообщение.
     *
     * <p>Без префикса: экран отключения и так занят только этим текстом.
     */
    public @NotNull Component kick(@NotNull String path, @NotNull String fallback) {
        return miniMessage.deserialize(raw("kick." + path, fallback));
    }

    /** Произвольная строка MiniMessage. */
    public @NotNull Component parse(@NotNull String value) {
        return miniMessage.deserialize(value);
    }

    /**
     * Готовая строка с префиксом — для сообщений, собираемых на месте.
     *
     * <p>Нужна командам, которые строят текст из данных (список сессий,
     * состояние системы): выносить каждую такую строку в файл бессмысленно,
     * а префикс у них должен быть общий.
     */
    public @NotNull Component prefixedRaw(@NotNull String text) {
        return miniMessage.deserialize(prefix() + text);
    }

    /** Префикс сети. */
    public @NotNull String prefix() {
        return config.getString(ConfigFile.MESSAGES, "prefix", "");
    }

    private String raw(String path, String fallback) {
        return config.getString(ConfigFile.MESSAGES, path, fallback);
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
