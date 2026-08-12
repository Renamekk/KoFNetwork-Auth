package net.kofnetwork.auth.velocity.message;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Приветственная надпись на экране после успешного входа.
     *
     * <p>Та же, что висит в Limbo до входа: игрок видит бренд и в момент
     * ожидания, и в момент, когда его пустили. Без этого надпись пропадала
     * ровно тогда, когда вход наконец состоялся, — самый заметный момент
     * оставался пустым.
     *
     * <p>Возвращает пустое значение при {@code welcome.enabled: false}: вызывающему
     * не нужно знать, как называется настройка, ему нужно решение «показывать или
     * нет» одним ответом.
     *
     * @param playerName подставляется в подзаголовок вместо {@code <player>}
     */
    public @NotNull Optional<Title> welcomeTitle(@NotNull String playerName) {
        if (!config.getBoolean(ConfigFile.VELOCITY, "welcome.enabled", true)) {
            return Optional.empty();
        }
        Component header = plain("welcome-title",
                "<gradient:#FF2D2D:#FFD700:#FFFFFF><bold>KoFNetwork</bold></gradient>");
        Component subtitle = miniMessage.deserialize(substitute(
                raw("welcome-subtitle", "<gray>С возвращением, <white><player></white>!"),
                // Ник приходит от Velocity и ограничен набором символов Minecraft,
                // угловых скобок в нём быть не может — экранировать нечего.
                Map.of("player", playerName)));

        return Optional.of(Title.title(header, subtitle, Title.Times.times(
                titleTime("welcome.title.fade-in", Duration.ofMillis(250)),
                titleTime("welcome.title.stay", Duration.ofSeconds(3)),
                titleTime("welcome.title.fade-out", Duration.ofMillis(500)))));
    }

    private Duration titleTime(String path, Duration fallback) {
        return config.getDuration(ConfigFile.VELOCITY, path, fallback);
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
