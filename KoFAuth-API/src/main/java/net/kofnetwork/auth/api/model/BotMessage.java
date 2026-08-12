package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Сообщение боту из долговечной очереди.
 *
 * <p><b>Почему очередь, а не Pub/Sub.</b> Раньше боты подписывались на общий канал Redis
 * тем же паролем, что и Core. Это давало процессу на Python право читать и переписывать
 * любые ключи — состояния входа, привязки UUID, счётчики лимитов — и публиковать любые
 * доменные события от имени системы. При этом доставка всё равно оставалась ненадёжной:
 * Pub/Sub не помнит сообщений, и бот, перезапущенный на две секунды, терял все запросы
 * подтверждения, пришедшие за это время.
 *
 * <p>Очередь снимает обе беды сразу. Боты ходят только в {@code /api/bot} со своим ключом
 * и получают ровно то, что им адресовано; хранилищем служит таблица, поэтому короткий
 * простой бота ничего не теряет — сообщения ждут его возвращения.
 *
 * @param id       монотонно растущий номер; служит курсором доставки
 * @param platform кому адресовано
 * @param payload  скалярные поля сообщения; секретов здесь нет по построению
 */
public record BotMessage(
        long id,
        @NotNull BotPlatform platform,
        long recipientId,
        long chatId,
        @NotNull Kind kind,
        @NotNull Map<String, String> payload,
        @NotNull Instant createdAt,
        @NotNull Instant expiresAt
) {

    /** Что боту нужно сделать. */
    public enum Kind {

        /** Показать владельцу кнопки «Войти» и «Отклонить». */
        LOGIN_APPROVAL,

        /** Сообщить, что запрос подтверждения больше не действует. */
        LOGIN_APPROVAL_RESOLVED,

        /** Уведомить о входе в аккаунт. */
        LOGIN_NOTICE,

        /** Уведомить о событии безопасности. */
        SECURITY_NOTICE,

        /**
         * Опубликовать выданный в игре код привязки в служебном канале.
         *
         * <p>Адресата у этого сообщения нет: игрок ещё не привязан, и написать
         * ему в личные сообщения невозможно — именно этим привязка и начинается.
         * Поэтому {@code recipientId} равен нулю, а канал бот берёт из своей
         * конфигурации.
         *
         * <p><b>Код предъявительский.</b> Кто первым отправит его боту, тот и
         * получит привязку, поэтому публикация в канал, видимый посторонним,
         * означает передачу аккаунта тому, кто быстрее. Включать её осмысленно
         * только для канала, в который пишет один игрок, либо на время
         * разбирательства. Выключатель — {@code discord.yml → link.post-code-to-channel}.
         */
        LINK_CODE
    }

    public BotMessage {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public @NotNull BotMessage withId(long newId) {
        return new BotMessage(newId, platform, recipientId, chatId, kind, payload,
                createdAt, expiresAt);
    }

    public @NotNull String get(@NotNull String key) {
        return payload.getOrDefault(key, "");
    }
}
