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
 * <p><b>Событие больше не адресовано ботам.</b> Раньше именно оно везло запрос до
 * Telegram и Discord через Redis Pub/Sub, и это давало сразу две беды. Канал не помнит
 * сообщений, поэтому бот, перезапущенный на пару секунд, терял запрос — игрок ждал
 * кнопку, которой не будет. А доступ к каналу требовал полномочий Redis, то есть права
 * переписывать состояние входа всей сети. Теперь запрос кладётся в долговечную очередь
 * ({@code bot_outbox}), а это событие остаётся локальным — для метрик и аудита.
 *
 * <p>Секрета в нём нет: {@link #approvalPublicId()} — идентификатор запроса, который
 * ничего не открывает без проверки владельца кнопки на стороне сервера.
 *
 * <p>Тип поля {@code accountId} — {@code Long}, а не {@code long}: аксессор записи
 * реализует {@link AuthEvent#accountId()}, а примитив не переопределяет ссылочный тип.
 * Здесь значение не бывает пустым — без найденного аккаунта второго фактора нет.
 *
 * @param username ник нужен подписчику для текста сообщения; лезть за ним в базу
 *                 ради одной строки незачем
 * @param attemptId какая попытка входа ждёт решения
 */
public record LoginApprovalRequestedEvent(
        @NotNull Long accountId,
        @NotNull String username,
        @NotNull TwoFactorMethod method,
        @NotNull String approvalPublicId,
        @NotNull String attemptId,
        @NotNull AuthContext context,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public LoginApprovalRequestedEvent {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(approvalPublicId, "approvalPublicId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static @NotNull LoginApprovalRequestedEvent of(long accountId,
                                                          @NotNull String username,
                                                          @NotNull TwoFactorMethod method,
                                                          @NotNull String approvalPublicId,
                                                          @NotNull String attemptId,
                                                          @NotNull AuthContext context) {
        return new LoginApprovalRequestedEvent(accountId, username, method, approvalPublicId,
                attemptId, context, Instant.now());
    }

    /**
     * Доставка идёт очередью, а не шиной.
     *
     * <p>Рассылать это событие по сети незачем: единственный потребитель, который был у
     * него на удалённой стороне, — бот, а бот теперь читает {@code /api/bot/events}.
     */
    @Override
    public boolean isDistributed() {
        return false;
    }
}
