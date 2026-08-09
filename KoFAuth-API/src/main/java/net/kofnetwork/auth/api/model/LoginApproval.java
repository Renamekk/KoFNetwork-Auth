package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Одноразовое подтверждение входа, выпущенное сервером.
 *
 * <p><b>Чем это отличается от прежнего кода подтверждения.</b> Раньше вторым фактором
 * служил токен {@code LOGIN_APPROVAL} — предъявительский и ни к чему не привязанный.
 * Из него следовало три беды. Токен можно было выпустить командой бота вообще без
 * попытки входа и предъявить позже, получив сессию без пароля. Предъявить его мог кто
 * угодно: владельца кнопки никто не сверял. И, наконец, погашение токена не завершало
 * ту попытку входа, ради которой он выпускался, — вместо этого создавалась отдельная
 * сессия типа {@code TELEGRAM}, а игрок оставался стоять в Limbo.
 *
 * <p>Подтверждение существует <em>только</em> как продолжение конкретной попытки входа
 * и хранит всё, что нужно, чтобы её завершить и чтобы никто посторонний этого не сделал:
 * <ul>
 *   <li>{@link #accountId()} и {@link #playerUuid()} — чей вход подтверждается;</li>
 *   <li>{@link #attemptId()} — какая именно попытка. Повторный {@code /login} выпускает
 *       новую, и решение по прежней уже ничего не откроет;</li>
 *   <li>{@link #platform()} и {@link #recipientId()} — кому показана кнопка. Нажатие
 *       принимается только от этого идентификатора мессенджера;</li>
 *   <li>{@link #status()} — решение, которое принимается ровно один раз.</li>
 * </ul>
 *
 * <p>Секрета в этой записи нет: {@link #publicId()} едет в {@code callback_data} кнопки
 * и сам по себе ничего не открывает — нажатие проверяется по владельцу.
 *
 * @param recipientId идентификатор пользователя мессенджера (Telegram user id либо
 *                    Discord user id), которому адресована кнопка
 * @param chatId      чат для доставки; у Discord совпадает с {@code recipientId}
 */
public record LoginApproval(
        long id,
        @NotNull String publicId,
        long accountId,
        @NotNull String username,
        @Nullable UUID playerUuid,
        @NotNull String attemptId,
        @NotNull EventSource requestSource,
        @NotNull DevicePlatform requestPlatform,
        @Nullable String userAgent,
        @Nullable String deviceFingerprint,
        @NotNull BotPlatform platform,
        long recipientId,
        long chatId,
        @NotNull IpAddress requestIp,
        @Nullable String server,
        @Nullable String country,
        @Nullable String city,
        @NotNull ApprovalStatus status,
        @NotNull Instant issuedAt,
        @NotNull Instant expiresAt,
        @Nullable Instant decidedAt,
        @Nullable Long decidedBy,
        @Nullable String browserProofHash,
        @Nullable Long sessionId,
        @Nullable Instant exchangeConsumedAt
) {

    /** Сколько живёт подтверждение, если конфигурация не говорит иного. */
    public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(2);

    public LoginApproval {
        Objects.requireNonNull(publicId, "publicId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(requestSource, "requestSource");
        Objects.requireNonNull(requestPlatform, "requestPlatform");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(requestIp, "requestIp");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Ожидает ли подтверждение решения прямо сейчас. */
    public boolean isPending(@NotNull Instant at) {
        return status == ApprovalStatus.PENDING && at.isBefore(expiresAt);
    }

    public boolean isExpired(@NotNull Instant at) {
        return !at.isBefore(expiresAt);
    }

    /**
     * Тот ли это человек, кому показана кнопка.
     *
     * <p>Проверяется и платформа, и идентификатор: без платформы Discord-пользователь
     * с числовым идентификатором, совпавшим с чьим-то Telegram-идентификатором, смог бы
     * подтвердить чужой вход.
     */
    public boolean belongsTo(@NotNull BotPlatform pressedOn, long pressedBy) {
        return platform == pressedOn && recipientId == pressedBy;
    }

    public @NotNull LoginApproval withId(long newId) {
        return new LoginApproval(newId, publicId, accountId, username, playerUuid, attemptId,
                requestSource, requestPlatform, userAgent, deviceFingerprint,
                platform, recipientId, chatId, requestIp, server, country, city,
                status, issuedAt, expiresAt, decidedAt, decidedBy, browserProofHash,
                sessionId, exchangeConsumedAt);
    }

    public @NotNull LoginApproval decided(@NotNull ApprovalStatus decision,
                                          @NotNull Instant at,
                                          long by) {
        return new LoginApproval(id, publicId, accountId, username, playerUuid, attemptId,
                requestSource, requestPlatform, userAgent, deviceFingerprint,
                platform, recipientId, chatId, requestIp, server, country, city,
                decision, issuedAt, expiresAt, at, by, browserProofHash, sessionId,
                exchangeConsumedAt);
    }

    /** Возвращает копию с сессией, созданной для исходной попытки. */
    public @NotNull LoginApproval completedBy(@NotNull Session session) {
        return new LoginApproval(id, publicId, accountId, username, playerUuid, attemptId,
                requestSource, requestPlatform, userAgent, deviceFingerprint,
                platform, recipientId, chatId, requestIp, server, country, city,
                status, issuedAt, expiresAt, decidedAt, decidedBy, browserProofHash,
                session.id(), exchangeConsumedAt);
    }

    /** Отмечает, что исходная WEB-вкладка уже получила свою пару токенов. */
    public @NotNull LoginApproval exchangedAt(@NotNull Instant at) {
        return new LoginApproval(id, publicId, accountId, username, playerUuid, attemptId,
                requestSource, requestPlatform, userAgent, deviceFingerprint,
                platform, recipientId, chatId, requestIp, server, country, city,
                status, issuedAt, expiresAt, decidedAt, decidedBy, browserProofHash, sessionId, at);
    }
}
