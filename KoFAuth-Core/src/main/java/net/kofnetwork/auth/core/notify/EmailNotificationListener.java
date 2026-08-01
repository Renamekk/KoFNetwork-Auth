package net.kofnetwork.auth.core.notify;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.AccountLoginEvent;
import net.kofnetwork.auth.api.event.events.BindingChangedEvent;
import net.kofnetwork.auth.api.event.events.PasswordChangedEvent;
import net.kofnetwork.auth.api.event.events.SuspiciousActivityEvent;
import net.kofnetwork.auth.api.model.EmailBinding;
import net.kofnetwork.auth.api.service.EmailService;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Отправка уведомлений на почту по доменным событиям.
 *
 * <p>Существует, чтобы сервисы не знали об уведомлениях. {@code AuthenticationService}
 * публикует «игрок вошёл» и на этом заканчивает; решение, слать ли письмо, кому и
 * какое, принимается здесь. Добавление нового канала уведомлений (Telegram, Discord)
 * не требует правки ни одного сервиса — только новый подписчик.
 *
 * <p><b>Не про каждый вход.</b> Уведомление уходит только когда вход выглядит
 * необычно: новое устройство или новая страна. Письмо на каждый вход приучает
 * игрока не читать уведомления вовсе, и настоящее предупреждение о взломе он
 * пролистает вместе с остальными.
 */
public final class EmailNotificationListener implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailNotificationListener.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final EmailService email;
    private final ConfigurationService config;
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();

    public EmailNotificationListener(@NotNull EmailService email,
                                     @NotNull ConfigurationService config) {
        this.email = email;
        this.config = config;
    }

    /**
     * Подписывается на события.
     *
     * <p>Дескрипторы сохраняются: без отписки при {@code /auth reload} останутся
     * висеть подписки от старых экземпляров, и каждое письмо начнёт приходить дважды.
     */
    public void register(@NotNull EventBus events) {
        subscriptions.add(events.subscribe(AccountLoginEvent.class, this::onLogin));
        subscriptions.add(events.subscribe(PasswordChangedEvent.class, this::onPasswordChanged));
        subscriptions.add(events.subscribe(SuspiciousActivityEvent.class, this::onSuspicious));
        subscriptions.add(events.subscribe(BindingChangedEvent.class, this::onBindingChanged));
    }

    // ------------------------------------------------------------------ обработчики

    private void onLogin(AccountLoginEvent event) {
        if (!event.isSuspicious()) {
            return;
        }
        boolean notifyDevice = config.getBoolean(ConfigFile.MAIL, "notifications.new-device", true);
        boolean notifyCountry = config.getBoolean(ConfigFile.MAIL, "notifications.new-country", true);

        if (event.newDevice() && !notifyDevice) {
            return;
        }
        if (!event.newDevice() && event.newCountry() && !notifyCountry) {
            return;
        }

        send(event.account().id(), EmailBinding::notifyLogin, "new-login", Map.of(
                "username", event.account().username(),
                "ip", event.context().ip().asMasked(),
                "location", location(event.context().country(), event.context().city()),
                "time", TIME.format(event.occurredAt()),
                "reason", event.newDevice() ? "новое устройство" : "новая страна"));
    }

    private void onPasswordChanged(PasswordChangedEvent event) {
        if (!config.getBoolean(ConfigFile.MAIL, "notifications.password-changed", true)) {
            return;
        }
        Long accountId = event.accountId();
        if (accountId == null) {
            return;
        }
        // Уведомление о смене пароля идёт независимо от настройки notify_login:
        // это событие безопасности, а не информационное.
        send(accountId, EmailBinding::notifySecurity, "security-alert", Map.of(
                "message", event.viaReset()
                        ? "Пароль вашего аккаунта был сброшен по коду из письма. "
                                + "Если это были не вы — немедленно обратитесь к администрации."
                        : "Пароль вашего аккаунта изменён. Если это были не вы — "
                                + "воспользуйтесь восстановлением доступа.",
                "time", TIME.format(event.occurredAt()),
                "ip", event.context().ip().asMasked()));
    }

    private void onSuspicious(SuspiciousActivityEvent event) {
        if (!event.requiresOwnerNotification()) {
            return;
        }
        Long accountId = event.accountId();
        if (accountId == null) {
            return;
        }
        send(accountId, EmailBinding::notifySecurity, "security-alert", Map.of(
                "message", describe(event),
                "time", TIME.format(event.occurredAt()),
                "ip", event.context().ip().asMasked()));
    }

    private void onBindingChanged(BindingChangedEvent event) {
        if (!config.getBoolean(ConfigFile.MAIL, "notifications.binding-changed", true)) {
            return;
        }
        Long accountId = event.accountId();
        if (accountId == null) {
            return;
        }
        // О привязке самой почты письмом не сообщаем: игрок только что получил
        // на этот адрес код подтверждения, второе письмо избыточно.
        if (event.binding() == BindingChangedEvent.BindingKind.EMAIL) {
            return;
        }
        send(accountId, EmailBinding::notifySecurity, "security-alert", Map.of(
                "message", "Изменена привязка " + event.binding()
                        + ": " + describeAction(event.action())
                        + (event.target() == null ? "" : " (" + event.target() + ")"),
                "time", TIME.format(event.occurredAt()),
                "ip", event.context().ip().asMasked()));
    }

    // ------------------------------------------------------------------ отправка

    /**
     * Отправляет письмо, если у аккаунта есть подтверждённая почта и включена
     * соответствующая настройка уведомлений.
     *
     * @param wanted проверка настройки конкретной привязки
     */
    private void send(long accountId,
                      java.util.function.Predicate<EmailBinding> wanted,
                      String template,
                      Map<String, String> variables) {
        if (!email.isConfigured()) {
            return;
        }
        email.findPrimary(accountId)
                .thenCompose(binding -> {
                    Optional<EmailBinding> usable = binding.filter(EmailBinding::isUsable)
                            .filter(wanted);
                    if (usable.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return email.sendTemplated(usable.get().email(), template, variables)
                            .thenApply(result -> null);
                })
                .exceptionally(e -> {
                    // Неотправленное уведомление не должно ничего ломать: игрок
                    // уже вошёл, пароль уже изменён, событие уже записано в аудит.
                    LOGGER.warn("Не удалось отправить уведомление '{}' аккаунту {}: {}",
                            template, accountId, e.getMessage());
                    return null;
                });
    }

    private static String location(String country, String city) {
        if (country == null && city == null) {
            return "неизвестно";
        }
        if (city == null) {
            return country;
        }
        return country == null ? city : country + ", " + city;
    }

    private static String describe(SuspiciousActivityEvent event) {
        return switch (event.type()) {
            case BRUTE_FORCE_DETECTED ->
                    "Зафиксирован подбор пароля к вашему аккаунту. Вход временно заблокирован.";
            case TOKEN_REPLAY_DETECTED ->
                    "Обнаружено повторное использование токена доступа. Все сессии завершены.";
            case SESSION_IP_MISMATCH ->
                    "Сессия вашего аккаунта использовалась с другого адреса и была завершена.";
            case PROXY_DETECTED ->
                    "Попытка входа через VPN или прокси.";
            case BOT_DETECTED ->
                    "Подозрительная автоматическая активность с вашего адреса.";
            case RECOVERY_CODE_USED ->
                    "Использован резервный код двухфакторной аутентификации.";
            case TOTP_DISABLED ->
                    "Двухфакторная аутентификация отключена.";
            default -> event.detail() == null
                    ? "Зафиксировано событие безопасности: " + event.type()
                    : event.detail();
        };
    }

    private static String describeAction(BindingChangedEvent.Action action) {
        return switch (action) {
            case LINKED -> "привязано";
            case UNLINKED -> "отвязано";
            case VERIFIED -> "подтверждено";
            case ENABLED -> "включено";
            case DISABLED -> "отключено";
        };
    }

    @Override
    public void close() {
        subscriptions.forEach(EventBus.Subscription::close);
        subscriptions.clear();
    }
}
