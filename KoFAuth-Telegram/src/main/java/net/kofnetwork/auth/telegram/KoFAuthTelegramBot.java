package net.kofnetwork.auth.telegram;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.core.KoFAuthCore;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Telegram-бот KoF Network.
 *
 * <p><b>Направление привязки.</b> Код выдаётся в игре или в личном кабинете и
 * вводится здесь командой {@code /link}. Обратный порядок сломал бы модель доверия:
 * если бы код выдавал бот по нику, любой знающий чужой ник привязал бы к себе
 * чужой аккаунт.
 *
 * <p><b>Подтверждение входа.</b> Кнопка «Это я» гасит одноразовый токен через
 * {@code completeApproval}. Повторное нажатие ничего не создаёт — токен уже
 * использован, и вторая сессия не появится.
 */
public final class KoFAuthTelegramBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KoFAuthTelegramBot.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    /** Префиксы данных инлайн-кнопок. */
    private static final String APPROVE = "approve:";
    private static final String DENY = "deny:";

    private final KoFAuthCore core;
    private final TelegramClient client;

    public KoFAuthTelegramBot(KoFAuthCore core, TelegramClient client) {
        this.core = core;
        this.client = client;
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                onCallback(update);
                return;
            }
            if (update.hasMessage() && update.getMessage().hasText()) {
                onMessage(update);
            }
        } catch (RuntimeException e) {
            // Исключение в обработчике не должно останавливать опрос обновлений:
            // одна кривая команда не может выключить бота для всех.
            LOGGER.error("Ошибка обработки обновления", e);
        }
    }

    // ------------------------------------------------------------------ команды

    private void onMessage(Update update) {
        long chatId = update.getMessage().getChatId();
        long telegramId = update.getMessage().getFrom().getId();
        String text = update.getMessage().getText().trim();

        String[] parts = text.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].trim() : "";

        // В группах команды приходят с суффиксом @имя_бота.
        int at = command.indexOf('@');
        if (at > 0) {
            command = command.substring(0, at);
        }

        switch (command) {
            case "/start" -> send(chatId, """
                    <b>KoF Network</b>

                    Я умею подтверждать вход в игру и присылать оповещения безопасности.

                    Чтобы начать, возьмите код в игре командой <code>/telegram</code>
                    или в личном кабинете и пришлите мне:
                    <code>/link КОД</code>

                    Дальше будут доступны:
                    /profile — сведения об аккаунте
                    /devices — устройства
                    /history — история входов
                    /security — состояние защиты
                    /unlink — отвязать аккаунт""");

            case "/link" -> link(chatId, telegramId, argument);
            case "/unlink" -> withAccount(chatId, telegramId, account -> unlink(chatId, account));
            case "/profile" -> withAccount(chatId, telegramId, account -> profile(chatId, account));
            case "/devices" -> withAccount(chatId, telegramId, account -> devices(chatId, account));
            case "/history" -> withAccount(chatId, telegramId, account -> history(chatId, account));
            case "/security" -> withAccount(chatId, telegramId, account -> security(chatId, account));
            case "/login" -> send(chatId,
                    "Подтверждение входа приходит сюда автоматически, когда вы заходите в игру. "
                            + "Отдельная команда не нужна.");
            default -> send(chatId, "Неизвестная команда. Наберите /start.");
        }
    }

    private void link(long chatId, long telegramId, String code) {
        if (code.isBlank()) {
            send(chatId, "Укажите код: <code>/link КОД</code>");
            return;
        }
        core.links().completeTelegramLink(code, telegramId, chatId, AuthContext.telegram())
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        send(chatId, "✅ Аккаунт привязан.\n\n"
                                + "Включить подтверждение входа кнопкой можно в личном кабинете "
                                + "или командой /security.");
                        return;
                    }
                    send(chatId, "❌ " + switch (result.errorCode() == null ? "" : result.errorCode()) {
                        case "TELEGRAM_ALREADY_LINKED" ->
                                "Этот Telegram уже привязан к другому аккаунту.";
                        case "CODE_INVALID" -> "Код недействителен или истёк.";
                        default -> "Не удалось привязать аккаунт.";
                    });
                });
    }

    private void unlink(long chatId, Account account) {
        core.links().unlinkTelegram(account.id(), AuthContext.telegram())
                .thenAccept(result -> send(chatId, result.isSuccess()
                        ? "Аккаунт отвязан. Подтверждение входа через Telegram отключено."
                        : "Не удалось отвязать аккаунт."));
    }

    private void profile(long chatId, Account account) {
        core.totp().isEnabled(account.id()).thenAccept(totp -> send(chatId, """
                <b>%s</b>

                Статус: %s
                Регистрация: %s
                Последний вход: %s
                Адрес: %s
                Расположение: %s
                Второй фактор: %s"""
                .formatted(
                        escape(account.username()),
                        account.status(),
                        TIME.format(account.registrationDate()),
                        account.lastLoginAt() == null ? "никогда" : TIME.format(account.lastLoginAt()),
                        account.lastLoginIp() == null ? "—" : account.lastLoginIp().asMasked(),
                        location(account.lastCountry(), account.lastCity()),
                        totp ? "TOTP включён" : (account.hasTwoFactor()
                                ? account.twoFactorMethods().toString() : "выключен"))));
    }

    private void devices(long chatId, Account account) {
        core.adminOperations().listDevices(account.id()).thenAccept(devices -> {
            if (devices.isEmpty()) {
                send(chatId, "Устройств пока нет.");
                return;
            }
            StringBuilder text = new StringBuilder("<b>Устройства</b>\n");
            devices.stream().limit(10).forEach(device -> text
                    .append("\n• ").append(escape(device.friendlyName()))
                    .append("\n  ").append(device.lastSeenIp().asMasked())
                    .append(" · ").append(TIME.format(device.lastSeenAt()))
                    .append(device.trusted() ? " · доверенное" : "")
                    .append(device.blocked() ? " · заблокировано" : ""));
            send(chatId, text.toString());
        });
    }

    private void history(long chatId, Account account) {
        core.audit().getLoginHistory(account.id(), 10, 0).thenAccept(history -> {
            if (history.isEmpty()) {
                send(chatId, "История пуста.");
                return;
            }
            StringBuilder text = new StringBuilder("<b>Последние входы</b>\n");
            history.forEach(entry -> text
                    .append("\n").append(entry.success() ? "✅" : "❌").append(' ')
                    .append(TIME.format(entry.at()))
                    .append("\n  ").append(entry.ipMasked())
                    .append(" · ").append(location(entry.country(), entry.city()))
                    .append(entry.success() ? "" : " · " + entry.result()));
            send(chatId, text.toString());
        });
    }

    private void security(long chatId, Account account) {
        core.links().findTelegram(account.id()).thenAccept(binding -> {
            boolean approval = binding.map(b -> b.loginApprovalEnabled()).orElse(false);
            send(chatId, """
                    <b>Защита аккаунта</b>

                    Подтверждение входа: %s
                    Уведомления: %s

                    Управление — в личном кабинете."""
                    .formatted(approval ? "включено" : "выключено",
                            binding.map(b -> b.notificationsEnabled()).orElse(false)
                                    ? "включены" : "выключены"));
        });
    }

    // ------------------------------------------------------------------ подтверждение входа

    /** Отправляет запрос подтверждения входа с двумя кнопками. */
    public void requestApproval(long chatId, String approvalToken, String username,
                                String ipMasked, String location) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .parseMode("HTML")
                .text("""
                        🔐 <b>Вход в аккаунт %s</b>

                        Адрес: %s
                        Расположение: %s

                        Это вы?"""
                        .formatted(escape(username), ipMasked, location))
                .replyMarkup(InlineKeyboardMarkup.builder()
                        .keyboardRow(new InlineKeyboardRow(
                                InlineKeyboardButton.builder()
                                        .text("✅ Это я")
                                        .callbackData(APPROVE + approvalToken)
                                        .build(),
                                InlineKeyboardButton.builder()
                                        .text("❌ Это не я")
                                        .callbackData(DENY + approvalToken)
                                        .build()))
                        .build())
                .build();
        execute(message);
    }

    private void onCallback(Update update) {
        var callback = update.getCallbackQuery();
        String data = callback.getData();
        long chatId = callback.getMessage().getChatId();
        int messageId = callback.getMessage().getMessageId();

        boolean approve = data.startsWith(APPROVE);
        String token = data.substring(approve ? APPROVE.length() : DENY.length());

        core.authentication().completeApproval(token, approve, AuthContext.telegram())
                .thenAccept(result -> {
                    String text = approve
                            ? (result.isSuccess()
                                    ? "✅ Вход подтверждён."
                                    : "⌛ Запрос устарел или уже обработан.")
                            : "❌ Вход отклонён. Если это были не вы — смените пароль.";
                    // Правим исходное сообщение, а не шлём новое: кнопки исчезают,
                    // и повторно нажать уже нечего.
                    edit(chatId, messageId, text);
                });
    }

    // ------------------------------------------------------------------ вспомогательное

    /** Находит привязанный аккаунт и выполняет действие. */
    private void withAccount(long chatId, long telegramId,
                             java.util.function.Consumer<Account> action) {
        core.links().findAccountByTelegramId(telegramId)
                .thenCompose(accountId -> accountId
                        .map(core.adminOperations()::findAccount)
                        .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())))
                .thenAccept(account -> {
                    if (account.isEmpty()) {
                        send(chatId, "Аккаунт не привязан. Возьмите код в игре и пришлите "
                                + "<code>/link КОД</code>.");
                        return;
                    }
                    action.accept(account.get());
                })
                .exceptionally(e -> {
                    LOGGER.error("Ошибка команды Telegram", e);
                    send(chatId, "Внутренняя ошибка. Попробуйте позже.");
                    return null;
                });
    }

    private static String location(String country, String city) {
        if (country == null && city == null) {
            return "неизвестно";
        }
        return city == null ? country : (country == null ? city : country + ", " + city);
    }

    /** Экранирует HTML: Telegram разбирает разметку, а ник приходит извне. */
    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** Публичная отправка — для подписчиков событий из {@link TelegramBootstrap}. */
    public void sendRaw(long chatId, String html) {
        send(chatId, html);
    }

    private void send(long chatId, String html) {
        execute(SendMessage.builder()
                .chatId(chatId)
                .parseMode("HTML")
                .disableWebPagePreview(true)
                .text(html)
                .build());
    }

    private void edit(long chatId, int messageId, String text) {
        execute(EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .parseMode("HTML")
                .text(text)
                .build());
    }

    private void execute(Object method) {
        try {
            if (method instanceof SendMessage message) {
                client.execute(message);
            } else if (method instanceof EditMessageText edit) {
                client.execute(edit);
            }
        } catch (Exception e) {
            // Игрок мог заблокировать бота — это норма, а не сбой системы.
            LOGGER.warn("Не удалось отправить сообщение в Telegram: {}", e.getMessage());
        }
    }
}
