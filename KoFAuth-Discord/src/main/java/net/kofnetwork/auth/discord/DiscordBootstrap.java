package net.kofnetwork.auth.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
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

import java.nio.file.Path;

/** Запуск Discord-бота. */
public final class DiscordBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordBootstrap.class);

    private DiscordBootstrap() {
        throw new AssertionError("Класс запуска не подлежит созданию");
    }

    public static void main(String[] args) throws Exception {
        Path configDirectory = Path.of(args.length > 0 ? args[0] : "./config");

        KoFAuthCore core = KoFAuthCore.start(configDirectory);
        Runtime.getRuntime().addShutdownHook(new Thread(core::close, "kofauth-discord-shutdown"));

        if (!core.config().getBoolean(ConfigFile.DISCORD, "enabled", false)) {
            LOGGER.error("Discord-бот выключен в discord.yml (enabled: false). Завершение.");
            core.close();
            return;
        }

        String token = core.config().getString(ConfigFile.DISCORD, "bot.token", "");
        if (token.isBlank()) {
            LOGGER.error("Не задан токен бота. Укажите его в discord.yml или "
                    + "в переменной KOFAUTH_DISCORD_BOT_TOKEN. Завершение.");
            core.close();
            return;
        }

        KoFAuthDiscordBot bot = new KoFAuthDiscordBot(core);

        // Привилегированные интенты не запрашиваем: боту нужны только slash-команды
        // и личные сообщения. Лишние интенты требуют одобрения Discord и дают
        // доступ к данным, которые нам не нужны.
        JDA jda = JDABuilder.createLight(token, GatewayIntent.DIRECT_MESSAGES)
                .disableCache(java.util.EnumSet.allOf(CacheFlag.class))
                .addEventListeners(bot)
                .build()
                .awaitReady();

        registerCommands(core, jda);
        subscribeToEvents(core, jda);

        LOGGER.info("Discord-бот запущен как {}", jda.getSelfUser().getName());

        Runtime.getRuntime().addShutdownHook(new Thread(jda::shutdown, "jda-shutdown"));
        Thread.currentThread().join();
    }

    /**
     * Регистрация slash-команд.
     *
     * <p>На конкретном сервере команды появляются мгновенно, глобально — до часа.
     * Поэтому при разработке указывают {@code guild-id}, а глобальную регистрацию
     * включают только в production.
     */
    private static void registerCommands(KoFAuthCore core, JDA jda) {
        String guildId = core.config().getString(ConfigFile.DISCORD, "bot.guild-id", "");

        if (guildId.isBlank()) {
            jda.updateCommands().addCommands(KoFAuthDiscordBot.commands()).queue();
            LOGGER.info("Slash-команды зарегистрированы глобально (появятся в течение часа)");
            return;
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            LOGGER.error("Сервер {} не найден: бот на него не добавлен. "
                    + "Команды не зарегистрированы.", guildId);
            return;
        }
        guild.updateCommands().addCommands(KoFAuthDiscordBot.commands()).queue();
        LOGGER.info("Slash-команды зарегистрированы на сервере {}", guild.getName());
    }

    /** Уведомления о событиях сети в личные сообщения. */
    private static void subscribeToEvents(KoFAuthCore core, JDA jda) {
        core.events().subscribe(RemoteEvent.class, event -> {
            if (event.accountId() == null) {
                return;
            }
            if (event.isType(AccountLoginEvent.class)
                    && (event.booleanAttribute("newDevice") || event.booleanAttribute("newCountry"))) {
                deliver(core, jda, event.accountId(), KoFAuthDiscordBot.approvalEmbedLike(
                        "Вход с " + (event.booleanAttribute("newDevice")
                                ? "нового устройства" : "новой страны"),
                        event.attribute("username", "?"),
                        event.attribute("ip", "?"),
                        event.attribute("country", "неизвестно")));
                return;
            }
            if (event.isType(LoginApprovalRequestedEvent.class)) {
                requestApproval(core, jda, event);
                return;
            }
            if (event.isType(SuspiciousActivityEvent.class)) {
                deliver(core, jda, event.accountId(), KoFAuthDiscordBot.approvalEmbedLike(
                        "Событие безопасности",
                        event.attribute("eventType", "—"),
                        event.attribute("ip", "?"),
                        event.attribute("detail", "")));
            }
        });
    }

    /**
     * Присылает владельцу кнопки подтверждения входа.
     *
     * <p>Событие приходит без токена: {@code LOGIN_APPROVAL} — предъявительский код,
     * и класть его в общий канал Pub/Sub нельзя. Токен выпускает этот процесс, у него
     * есть доступ к базе, и действующим остаётся ровно один код на вход.
     *
     * <p>Чужой метод пропускаем: при настроенных обоих ботах запрос должен уйти
     * в выбранный канал, а не продублироваться в оба.
     */
    private static void requestApproval(KoFAuthCore core, JDA jda, RemoteEvent event) {
        if (!TwoFactorMethod.DISCORD.name().equals(event.attribute("method", ""))) {
            return;
        }
        long accountId = event.accountId();

        core.links().findDiscord(accountId).thenAccept(binding -> {
            if (binding.isEmpty() || !binding.get().loginApprovalEnabled()) {
                LOGGER.warn("Запрос подтверждения для аккаунта {} без активной привязки", accountId);
                return;
            }
            core.tokens().issue(accountId, TokenType.LOGIN_APPROVAL, null, null)
                    .thenAccept(issued -> jda.retrieveUserById(binding.get().discordId()).queue(
                            user -> user.openPrivateChannel().queue(
                                    channel -> channel
                                            .sendMessageEmbeds(KoFAuthDiscordBot.approvalEmbed(
                                                    event.attribute("username", "?"),
                                                    event.attribute("ip", "?"),
                                                    event.attribute("country", "неизвестно")))
                                            .setActionRow(KoFAuthDiscordBot
                                                    .approvalButtons(issued.value()))
                                            .queue(success -> { },
                                                    failure -> LOGGER.debug(
                                                            "Личные сообщения закрыты: {}",
                                                            failure.getMessage())),
                                    failure -> LOGGER.debug("Не удалось открыть личный чат: {}",
                                            failure.getMessage())),
                            failure -> LOGGER.debug("Пользователь Discord не найден: {}",
                                    failure.getMessage())))
                    .exceptionally(e -> {
                        LOGGER.error("Не удалось выпустить токен подтверждения", e);
                        return null;
                    });
        });
    }

    /**
     * Отправляет уведомление в личные сообщения.
     *
     * <p>Пользователь мог закрыть личные сообщения — это норма, а не сбой:
     * ошибка гасится, остальные каналы уведомлений продолжают работать.
     */
    private static void deliver(KoFAuthCore core, JDA jda, long accountId,
                                net.dv8tion.jda.api.entities.MessageEmbed embed) {
        core.links().findDiscord(accountId).thenAccept(binding -> {
            if (binding.isEmpty() || !binding.get().notificationsEnabled()) {
                return;
            }
            jda.retrieveUserById(binding.get().discordId()).queue(
                    user -> user.openPrivateChannel().queue(
                            channel -> channel.sendMessageEmbeds(embed).queue(
                                    success -> { },
                                    failure -> LOGGER.debug("Личные сообщения закрыты: {}",
                                            failure.getMessage())),
                            failure -> LOGGER.debug("Не удалось открыть личный чат: {}",
                                    failure.getMessage())),
                    failure -> LOGGER.debug("Пользователь Discord не найден: {}",
                            failure.getMessage()));
        });
    }
}
