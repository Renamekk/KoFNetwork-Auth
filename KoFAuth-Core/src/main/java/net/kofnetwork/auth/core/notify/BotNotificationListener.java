package net.kofnetwork.auth.core.notify;

import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.AccountLoginEvent;
import net.kofnetwork.auth.api.event.events.BindingChangedEvent;
import net.kofnetwork.auth.api.event.events.PasswordChangedEvent;
import net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent;
import net.kofnetwork.auth.api.event.events.SuspiciousActivityEvent;
import net.kofnetwork.auth.api.model.BotMessage;
import net.kofnetwork.auth.api.model.BotPlatform;
import net.kofnetwork.auth.api.repository.BotOutboxRepository;
import net.kofnetwork.auth.api.service.LinkService;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Перекладывает уведомления в очередь ботов.
 *
 * <p><b>Зачем понадобился этот слой.</b> Раньше боты сами подписывались на канал
 * Redis: узнавали о входах, сменах пароля и подозрительной активности напрямую из
 * общей шины. Для этого им выдавался пароль хранилища — то есть право переписывать
 * состояние входа всей сети и публиковать любые события от её имени. Здесь тот же
 * поток уведомлений собирается на стороне Core и кладётся в таблицу, откуда бот
 * забирает его по ключу {@code /api/bot}. Полномочий у бота ровно столько, сколько
 * нужно, чтобы написать человеку сообщение.
 *
 * <p>Побочная выгода — доставка перестала теряться. Pub/Sub не помнит сообщений, и
 * перезапуск бота на пару секунд стирал всё, что пришло за это время.
 *
 * <p>Адресаты определяются по привязкам с включёнными уведомлениями. Событие без
 * привязки просто никуда не кладётся: писать некому.
 */
public final class BotNotificationListener implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotNotificationListener.class);

    /**
     * Сколько сообщение ждёт бота.
     *
     * <p>Уведомление недельной давности человеку не нужно и только сбивает с толку;
     * при этом суток с запасом хватает на любой разумный простой бота.
     */
    private static final Duration NOTICE_LIFETIME = Duration.ofHours(24);

    private final BotOutboxRepository outbox;
    private final LinkService links;

    private final java.util.List<EventBus.Subscription> subscriptions = new java.util.ArrayList<>();

    public BotNotificationListener(@NotNull BotOutboxRepository outbox, @NotNull LinkService links) {
        this.outbox = outbox;
        this.links = links;
    }

    /** Подписывается на события, о которых стоит сообщать владельцу. */
    public void register(@NotNull EventBus events) {
        subscriptions.add(events.subscribe(AccountLoginEvent.class, event -> enqueue(
                event.account().id(), BotMessage.Kind.LOGIN_NOTICE, Map.of(
                        "event", "AccountLoginEvent",
                        "username", event.account().username(),
                        "ip", event.context().ip().asMasked(),
                        "at", event.occurredAt().toString()))));

        subscriptions.add(events.subscribe(PasswordChangedEvent.class, event -> enqueue(
                event.accountId(), BotMessage.Kind.SECURITY_NOTICE, Map.of(
                        "event", "PasswordChangedEvent",
                        "at", event.occurredAt().toString()))));

        subscriptions.add(events.subscribe(SuspiciousActivityEvent.class, event -> enqueue(
                event.accountId(), BotMessage.Kind.SECURITY_NOTICE, Map.of(
                        "event", "SuspiciousActivityEvent",
                        "ip", event.context().ip().asMasked(),
                        "at", event.occurredAt().toString()))));

        subscriptions.add(events.subscribe(SessionInvalidatedEvent.class, event -> {
            if (event.isNoop()) {
                // Отзыв, не затронувший ни одной сессии, — не новость для владельца.
                return;
            }
            enqueue(event.accountId(), BotMessage.Kind.SECURITY_NOTICE, Map.of(
                    "event", "SessionInvalidatedEvent",
                    "at", event.occurredAt().toString()));
        }));

        subscriptions.add(events.subscribe(BindingChangedEvent.class, event -> enqueue(
                event.accountId(), BotMessage.Kind.SECURITY_NOTICE, Map.of(
                        "event", "BindingChangedEvent",
                        "at", event.occurredAt().toString()))));
    }

    /** Кладёт сообщение всем привязанным платформам с включёнными уведомлениями. */
    private void enqueue(Long accountId, BotMessage.Kind kind, Map<String, String> payload) {
        if (accountId == null) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plus(NOTICE_LIFETIME);

        links.findTelegram(accountId)
                .thenCompose(binding -> binding
                        .filter(net.kofnetwork.auth.api.model.TelegramBinding::notificationsEnabled)
                        .map(b -> append(BotPlatform.TELEGRAM, b.telegramId(), b.chatId(),
                                kind, payload, now, expiresAt))
                        .orElseGet(() -> CompletableFuture.completedFuture(null)))
                .exceptionally(e -> log(accountId, e));

        links.findDiscord(accountId)
                .thenCompose(binding -> binding
                        .filter(net.kofnetwork.auth.api.model.DiscordBinding::notificationsEnabled)
                        // У Discord отдельного чата нет: бот пишет в личные сообщения,
                        // поэтому получатель и «чат» — один идентификатор.
                        .map(b -> append(BotPlatform.DISCORD, b.discordId(), b.discordId(),
                                kind, payload, now, expiresAt))
                        .orElseGet(() -> CompletableFuture.completedFuture(null)))
                .exceptionally(e -> log(accountId, e));
    }

    private CompletableFuture<Void> append(BotPlatform platform, long recipientId, long chatId,
                                            BotMessage.Kind kind, Map<String, String> payload,
                                            Instant now, Instant expiresAt) {
        Map<String, String> copy = new LinkedHashMap<>(payload);
        return outbox.append(new BotMessage(0L, platform, recipientId, chatId, kind, copy,
                        now, expiresAt))
                .thenApply(ignored -> (Void) null);
    }

    private Void log(long accountId, Throwable failure) {
        // Недоставленное уведомление — потеря, но не повод ронять обработку события:
        // вход, смена пароля и отзыв сессии уже состоялись и от этого не зависят.
        LOGGER.warn("Уведомление для аккаунта {} не поставлено в очередь", accountId, failure);
        return null;
    }

    @Override
    public void close() {
        subscriptions.forEach(EventBus.Subscription::close);
        subscriptions.clear();
    }
}
