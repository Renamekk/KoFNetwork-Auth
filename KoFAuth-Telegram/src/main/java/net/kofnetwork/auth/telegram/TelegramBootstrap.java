package net.kofnetwork.auth.telegram;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.event.events.AccountLoginEvent;
import net.kofnetwork.auth.api.event.events.LoginApprovalRequestedEvent;
import net.kofnetwork.auth.api.event.events.RemoteEvent;
import net.kofnetwork.auth.api.event.events.SuspiciousActivityEvent;
import net.kofnetwork.auth.api.model.TokenType;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import net.kofnetwork.auth.core.KoFAuthCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.nio.file.Path;

/**
 * Запуск Telegram-бота.
 *
 * <p>Отдельный процесс: делит с игровой сетью только MySQL и Redis. Уведомления
 * приходят через {@link RemoteEvent} — события, отправленные другими узлами
 * в общий канал Pub/Sub.
 */
public final class TelegramBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(TelegramBootstrap.class);

    private TelegramBootstrap() {
        throw new AssertionError("Класс запуска не подлежит созданию");
    }

    public static void main(String[] args) throws Exception {
        Path configDirectory = Path.of(args.length > 0 ? args[0] : "./config");

        KoFAuthCore core = KoFAuthCore.start(configDirectory);
        Runtime.getRuntime().addShutdownHook(new Thread(core::close, "kofauth-telegram-shutdown"));

        if (!core.config().getBoolean(ConfigFile.TELEGRAM, "enabled", false)) {
            LOGGER.error("Telegram-бот выключен в telegram.yml (enabled: false). Завершение.");
            core.close();
            return;
        }

        String token = core.config().getString(ConfigFile.TELEGRAM, "bot.token", "");
        if (token.isBlank()) {
            LOGGER.error("Не задан токен бота. Укажите его в telegram.yml или "
                    + "в переменной KOFAUTH_TELEGRAM_BOT_TOKEN. Завершение.");
            core.close();
            return;
        }

        TelegramClient client = new OkHttpTelegramClient(token);
        KoFAuthTelegramBot bot = new KoFAuthTelegramBot(core, client);

        subscribeToEvents(core, bot);

        try (var application = new TelegramBotsLongPollingApplication()) {
            application.registerBot(token, bot);
            LOGGER.info("Telegram-бот запущен");
            // Опрос идёт в потоках библиотеки; главный поток просто ждёт
            // сигнала остановки.
            Thread.currentThread().join();
        } finally {
            core.close();
        }
    }

    /**
     * Подписка на события сети.
     *
     * <p>Слушаем и локальные, и удалённые события. Локальные приходят, если этот
     * же процесс что-то сделал (редко); удалённые — от прокси и веб-API, где
     * происходит основная активность.
     */
    private static void subscribeToEvents(KoFAuthCore core, KoFAuthTelegramBot bot) {
        core.events().subscribe(RemoteEvent.class, event -> {
            if (event.accountId() == null) {
                return;
            }
            if (event.isType(AccountLoginEvent.class)) {
                notifyLogin(core, bot, event);
                return;
            }
            if (event.isType(LoginApprovalRequestedEvent.class)) {
                requestApproval(core, bot, event);
                return;
            }
            if (event.isType(SuspiciousActivityEvent.class)) {
                notifySuspicious(core, bot, event);
            }
        });
    }

    /**
     * Показывает владельцу кнопки подтверждения входа.
     *
     * <p>Событие приходит без токена — его выпускает этот процесс. Так предъявительский
     * код не попадает в общий канал Pub/Sub, и действующим остаётся ровно один.
     *
     * <p>Запрос от чужого метода игнорируется: при двух настроенных ботах сообщение
     * должно уйти в тот канал, который выбран вторым фактором, а не в оба сразу.
     */
    private static void requestApproval(KoFAuthCore core, KoFAuthTelegramBot bot,
                                        RemoteEvent event) {
        if (!TwoFactorMethod.TELEGRAM.name().equals(event.attribute("method", ""))) {
            return;
        }
        long accountId = event.accountId();

        core.links().findTelegram(accountId).thenAccept(binding -> {
            if (binding.isEmpty() || !binding.get().loginApprovalEnabled()) {
                // Привязка снята или подтверждение выключено уже после того, как
                // сервис выбрал этот метод. Токен не выпускаем: некому предъявлять.
                LOGGER.warn("Запрос подтверждения для аккаунта {} без активной привязки", accountId);
                return;
            }
            core.tokens().issue(accountId, TokenType.LOGIN_APPROVAL, null, null)
                    .thenAccept(issued -> bot.requestApproval(
                            binding.get().chatId(),
                            issued.value(),
                            event.attribute("username", "?"),
                            event.attribute("ip", "?"),
                            event.attribute("country", "неизвестно")))
                    .exceptionally(e -> {
                        LOGGER.error("Не удалось выпустить токен подтверждения", e);
                        return null;
                    });
        });
    }

    private static void notifyLogin(KoFAuthCore core, KoFAuthTelegramBot bot, RemoteEvent event) {
        // Уведомляем только о необычном входе: сообщение на каждый вход приучает
        // не читать уведомления, и настоящее предупреждение будет пролистано.
        if (!event.booleanAttribute("newDevice") && !event.booleanAttribute("newCountry")) {
            return;
        }
        deliver(core, bot, event.accountId(), """
                ⚠️ <b>Вход с %s</b>

                Аккаунт: %s
                Адрес: %s
                Расположение: %s

                Если это были не вы — смените пароль."""
                .formatted(
                        event.booleanAttribute("newDevice") ? "нового устройства" : "новой страны",
                        event.attribute("username", "?"),
                        event.attribute("ip", "?"),
                        event.attribute("country", "неизвестно")));
    }

    private static void notifySuspicious(KoFAuthCore core, KoFAuthTelegramBot bot,
                                          RemoteEvent event) {
        deliver(core, bot, event.accountId(), """
                🚨 <b>Событие безопасности</b>

                %s
                Адрес: %s"""
                .formatted(event.attribute("eventType", "—"), event.attribute("ip", "?")));
    }

    /** Доставляет сообщение владельцу, если у него включены уведомления. */
    private static void deliver(KoFAuthCore core, KoFAuthTelegramBot bot,
                                long accountId, String html) {
        core.links().findTelegram(accountId).thenAccept(binding -> {
            if (binding.isEmpty() || !binding.get().notificationsEnabled()) {
                return;
            }
            bot.sendRaw(binding.get().chatId(), html);
        });
    }
}
