package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * Мессенджер, через который система разговаривает с владельцем аккаунта.
 *
 * <p>Отдельный тип, а не {@link TwoFactorMethod}: второй фактор — это <em>роль</em>
 * мессенджера в одном сценарии, а платформа нужна и там, где второго фактора нет
 * вовсе — при доставке уведомлений и при разборе запроса от бота. Прежде роль и
 * платформа не различались, и Discord приезжал в код, который умел только Telegram:
 * {@code AuthContext.telegram()} проставлялся любому запросу от бота, поэтому вход,
 * подтверждённый в Discord, записывался в историю как телеграмный.
 */
public enum BotPlatform {

    TELEGRAM,
    DISCORD;

    /** Соответствующий метод второго фактора. */
    public @NotNull TwoFactorMethod asTwoFactorMethod() {
        return this == TELEGRAM ? TwoFactorMethod.TELEGRAM : TwoFactorMethod.DISCORD;
    }

    /** Платформа, отвечающая методу второго фактора, если он относится к мессенджеру. */
    public static @NotNull Optional<BotPlatform> ofTwoFactorMethod(@Nullable TwoFactorMethod method) {
        if (method == TwoFactorMethod.TELEGRAM) {
            return Optional.of(TELEGRAM);
        }
        return method == TwoFactorMethod.DISCORD ? Optional.of(DISCORD) : Optional.empty();
    }

    /** Разбор из строки запроса. Неизвестное значение — пустой результат, а не исключение. */
    public static @NotNull Optional<BotPlatform> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Источник события для аудита и контекста. */
    public @NotNull EventSource asEventSource() {
        return this == TELEGRAM ? EventSource.TELEGRAM : EventSource.DISCORD;
    }
}
