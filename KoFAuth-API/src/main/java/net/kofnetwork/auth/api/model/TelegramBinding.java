package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Привязка Telegram. Соответствует строке {@code telegram}.
 *
 * <p>Связь 1:1 в обе стороны: один аккаунт — один Telegram, один Telegram — один аккаунт.
 * Второе ограничение существеннее первого: без него владелец одного Telegram-аккаунта
 * мог бы держать подтверждение входа для десятка игровых аккаунтов, что превращает
 * второй фактор в общий ключ.
 *
 * @param telegramId идентификатор пользователя Telegram
 * @param chatId     чат для уведомлений; для личных сообщений совпадает с {@code telegramId},
 *                   но хранится отдельно, потому что это разные сущности в Bot API
 */
public record TelegramBinding(
        long id,
        long accountId,
        long telegramId,
        long chatId,
        @Nullable String username,
        @Nullable String firstName,
        @Nullable String lastName,
        @Nullable String languageCode,
        boolean notificationsEnabled,
        boolean loginApprovalEnabled,
        @NotNull Instant linkedAt,
        @NotNull Instant updatedAt
) {

    public TelegramBinding {
        Objects.requireNonNull(linkedAt, "linkedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static @NotNull TelegramBinding create(long accountId, long telegramId, long chatId) {
        Instant now = Instant.now();
        return new TelegramBinding(0L, accountId, telegramId, chatId,
                null, null, null, null, true, false, now, now);
    }

    /** Обновляет профильные данные: в Telegram их можно менять в любой момент. */
    public @NotNull TelegramBinding withProfile(@Nullable String newUsername,
                                                @Nullable String newFirstName,
                                                @Nullable String newLastName,
                                                @Nullable String newLanguageCode) {
        return new TelegramBinding(id, accountId, telegramId, chatId,
                newUsername, newFirstName, newLastName, newLanguageCode,
                notificationsEnabled, loginApprovalEnabled, linkedAt, Instant.now());
    }

    public @NotNull TelegramBinding withNotifications(boolean enabled) {
        return new TelegramBinding(id, accountId, telegramId, chatId,
                username, firstName, lastName, languageCode,
                enabled, loginApprovalEnabled, linkedAt, Instant.now());
    }

    public @NotNull TelegramBinding withLoginApproval(boolean enabled) {
        return new TelegramBinding(id, accountId, telegramId, chatId,
                username, firstName, lastName, languageCode,
                notificationsEnabled, enabled, linkedAt, Instant.now());
    }

    public @NotNull TelegramBinding withId(long newId) {
        return new TelegramBinding(newId, accountId, telegramId, chatId,
                username, firstName, lastName, languageCode,
                notificationsEnabled, loginApprovalEnabled, linkedAt, updatedAt);
    }

    /** Отображаемое имя: {@code @username}, иначе имя и фамилия, иначе числовой id. */
    public @NotNull String displayName() {
        if (username != null && !username.isBlank()) {
            return "@" + username;
        }
        StringBuilder sb = new StringBuilder();
        if (firstName != null && !firstName.isBlank()) {
            sb.append(firstName);
        }
        if (lastName != null && !lastName.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(lastName);
        }
        return sb.isEmpty() ? String.valueOf(telegramId) : sb.toString();
    }
}
