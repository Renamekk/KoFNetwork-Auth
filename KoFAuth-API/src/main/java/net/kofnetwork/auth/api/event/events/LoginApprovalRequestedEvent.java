package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;

/**
 * Вход ожидает подтверждения владельцем во внешнем канале — Telegram или Discord.
 *
 * <p>Публикуется, когда пароль уже проверен, а вторым фактором выбран бот. Слушает
 * его процесс бота: он присылает владельцу сообщение с кнопками «Это я» / «Это не я».
 *
 * <p><b>Токена подтверждения здесь нет намеренно.</b> Событие уезжает в общий канал
 * Pub/Sub, а токен {@code LOGIN_APPROVAL} — предъявительский: кто его прочитал, тот
 * и войдёт. Вместо пересылки токен выпускает сам бот — у него есть доступ к базе, —
 * и живым в каждый момент остаётся ровно один код, как и для восстановления пароля.
 *
 * <p>Тип поля {@code accountId} — {@code Long}, а не {@code long}: аксессор записи
 * реализует {@link AuthEvent#accountId()}, а примитив не переопределяет ссылочный тип.
 * Здесь значение не бывает пустым — без найденного аккаунта второго фактора нет.
 *
 * @param username ник нужен подписчику для текста сообщения; лезть за ним в базу
 *                 ради одной строки незачем
 */
public record LoginApprovalRequestedEvent(
        @NotNull Long accountId,
        @NotNull String username,
        @NotNull TwoFactorMethod method,
        @NotNull AuthContext context,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public LoginApprovalRequestedEvent {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static @NotNull LoginApprovalRequestedEvent of(long accountId,
                                                          @NotNull String username,
                                                          @NotNull TwoFactorMethod method,
                                                          @NotNull AuthContext context) {
        return new LoginApprovalRequestedEvent(accountId, username, method, context, Instant.now());
    }
}
