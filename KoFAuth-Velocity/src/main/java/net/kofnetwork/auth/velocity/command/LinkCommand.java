package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.BotMessage;
import net.kofnetwork.auth.api.model.BotPlatform;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.api.service.LinkService;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.velocity.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Команды {@code /telegram} и {@code /discord} — привязка мессенджера к аккаунту.
 *
 * <p><b>Зачем команда в игре.</b> Код привязки обязан выдаваться там, где доказано
 * владение игровым аккаунтом, то есть в игре, и вводиться в мессенджере. Обратное
 * направление — «бот выдаёт код, игрок вводит его в игре» — позволило бы привязать
 * свой Telegram к чужому нику: достаточно знать ник. Личный кабинет выдаёт такой же
 * код, но требует входа по паролю на сайте; команда в игре нужна тем, кто до сайта
 * не дошёл, а таких большинство.
 *
 * <p>Подкоманды: без аргументов — состояние и код при отсутствии привязки,
 * {@code unlink} — отвязать, {@code approval on|off} — подтверждение входа кнопкой.
 */
public final class LinkCommand implements SimpleCommand {

    /** Какой мессенджер обслуживает этот экземпляр команды. */
    public enum Kind {
        TELEGRAM("Telegram", ConfigFile.TELEGRAM),
        DISCORD("Discord", ConfigFile.DISCORD);

        private final String title;
        private final ConfigFile config;

        Kind(String title, ConfigFile config) {
            this.title = title;
            this.config = config;
        }

        /** Имя команды в игре: {@code /telegram}, {@code /discord}. */
        public @NotNull String command() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final KoFAuthCore core;
    private final Kind kind;
    private final MessageService messages;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public LinkCommand(@NotNull KoFAuthCore core,
                       @NotNull Kind kind,
                       @NotNull MessageService messages,
                       @NotNull Logger logger) {
        this.core = core;
        this.kind = kind;
        this.messages = messages;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(miniMessage.deserialize("<red>Команда доступна только в игре."));
            return;
        }
        if (!core.config().getBoolean(kind.config, "enabled", false)) {
            player.sendMessage(prefixed("<yellow>" + kind.title + " на сервере не подключён."));
            return;
        }

        String[] args = invocation.arguments();

        withAuthenticated(player, account -> {
            if (args.length == 0) {
                statusOrCode(player, account);
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "unlink", "отвязать" -> unlink(player, account);
                case "code", "link", "привязать" -> issueCode(player, account);
                case "approval", "вход" -> approval(player, account, args);
                default -> usage(player);
            }
        });
    }

    /**
     * Выполняет действие только для вошедшего игрока.
     *
     * <p>Состояние проверяется в Redis, а не по факту нахождения на лобби: игрок мог
     * оказаться там из-за ошибки маршрутизации, и его текущий сервер — не признак
     * пройденной аутентификации.
     */
    private void withAuthenticated(Player player, Consumer<Account> action) {
        core.sessions().getState(player.getUniqueId()).thenAccept(state -> {
            if (!state.isAuthenticated()) {
                player.sendMessage(prefixed("<red>Сначала войдите в аккаунт."));
                return;
            }
            core.authentication().findAccount(player.getUsername()).thenAccept(found -> {
                if (found.isEmpty()) {
                    player.sendMessage(prefixed("<red>Аккаунт не найден."));
                    return;
                }
                action.accept(found.get());
            });
        }).exceptionally(e -> {
            logger.error("Ошибка команды /{} для {}", kind.command(), player.getUsername(), e);
            player.sendMessage(prefixed("<red>Внутренняя ошибка. Попробуйте позже."));
            return null;
        });
    }

    /** Привязан — показываем состояние; не привязан — сразу выдаём код. */
    private void statusOrCode(Player player, Account account) {
        findBinding(account.id()).thenAccept(binding -> {
            if (binding.isEmpty()) {
                issueCode(player, account);
                return;
            }
            Binding value = binding.get();
            player.sendMessage(prefixed("<white>" + kind.title + ": <gray>" + value.displayName()));
            player.sendMessage(miniMessage.deserialize(value.loginApproval()
                    ? "  <green>Подтверждение входа включено"
                    : "  <yellow>Подтверждение входа выключено <gray>— /"
                            + kind.command() + " approval on"));
            player.sendMessage(miniMessage.deserialize(
                    "  <gray>Отвязать: <white>/" + kind.command() + " unlink"));
        });
    }

    /**
     * Выдаёт код привязки и показывает его кликабельно.
     *
     * <p><b>Почему кликабельно.</b> Раньше игроку оставалось переписать
     * восьмизначный код в другое приложение руками — на телефоне это ровно тот
     * барьер, из-за которого привязку не доводят до конца. Теперь код сам
     * копируется в буфер по нажатию, а у Telegram есть и прямой переход:
     * {@code t.me/бот?start=link_КОД} открывает чат с уже подставленным кодом,
     * и бот привязывает аккаунт сам, без единого набранного символа.
     *
     * <p>У Discord такого перехода нет и быть не может: slash-команду ссылкой не
     * предзаполнить. Поэтому там ссылка ведёт в канал привязки, а код едет туда
     * же отдельным сообщением, если это включено в {@code discord.yml}.
     */
    private void issueCode(Player player, Account account) {
        createCode(account.id(), contextOf(player)).thenAccept(result -> {
            if (!result.isSuccess()) {
                player.sendMessage(prefixed("<red>" + describe(result.errorCode())));
                return;
            }
            LinkService.LinkCode code = result.value();
            String command = "/link " + code.code();

            // Сам код — кнопка: нажатие кладёт его в буфер обмена.
            player.sendMessage(prefixed("<green>Код привязки: "
                    + "<click:copy_to_clipboard:'" + code.code() + "'>"
                    + "<hover:show_text:'<gray>Нажмите, чтобы скопировать код'>"
                    + "<white><bold>" + code.code() + "</bold></white></hover></click>"
                    + " <dark_gray>(нажмите, чтобы скопировать)"));

            deepLink(code.code()).map(LinkCommand::escapeTag).ifPresent(url ->
                    player.sendMessage(miniMessage.deserialize(
                            "  <click:open_url:'" + url + "'>"
                                    + "<hover:show_text:'<gray>" + url + "'>"
                                    + "<aqua><underlined>" + openLabel() + "</underlined></aqua>"
                                    + "</hover></click>")));

            // Запасной путь остаётся всегда: ссылку может не открыть лаунчер,
            // а перехода к slash-команде Discord не существует вовсе.
            player.sendMessage(miniMessage.deserialize(
                    "  <gray>Или отправьте <click:copy_to_clipboard:'" + command + "'>"
                            + "<hover:show_text:'<gray>Нажмите, чтобы скопировать команду'>"
                            + "<white>" + command + "</white></hover></click> <gray>"
                            + (kind == Kind.TELEGRAM ? "боту " + botName() : "боту в Discord")));

            player.sendMessage(miniMessage.deserialize(
                    "  <dark_gray>Код действует " + humanize(code.ttl())
                            + " и сгорает после первого использования."));

            publishToChannel(player, code);
        });
    }

    /**
     * Ссылка, ведущая прямо к привязке.
     *
     * <p>Telegram понимает параметр {@code start}: бот получает его первым
     * сообщением и завершает привязку сам. Discord ничего подобного не умеет,
     * поэтому там ссылка ведёт в канал или на приглашение — если они настроены.
     */
    private java.util.Optional<String> deepLink(String code) {
        if (kind == Kind.TELEGRAM) {
            String username = core.config().getString(ConfigFile.TELEGRAM, "bot.username", "").trim();
            if (username.isBlank()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of("https://t.me/"
                    + username.replace("@", "") + "?start=link_" + code);
        }

        String guild = core.config().getString(ConfigFile.DISCORD, "bot.guild-id", "").trim();
        String channel = core.config().getString(ConfigFile.DISCORD, "account-channel-id", "").trim();
        if (!guild.isBlank() && !channel.isBlank()) {
            return java.util.Optional.of(
                    "https://discord.com/channels/" + guild + "/" + channel);
        }
        String invite = core.config().getString(ConfigFile.DISCORD, "invite-url", "").trim();
        return invite.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(invite);
    }

    private String openLabel() {
        return kind == Kind.TELEGRAM
                ? "▸ Открыть Telegram и привязать автоматически"
                : "▸ Открыть канал привязки в Discord";
    }

    /**
     * Дублирует код в служебный канал Discord.
     *
     * <p><b>Код предъявительский.</b> Кто первым отправит его боту, тот и получит
     * привязку — сам код ничьей личности не удостоверяет. Поэтому публикация в
     * канал, доступный посторонним, равносильна передаче аккаунта тому, кто
     * быстрее прочитал. Возможность выключена по умолчанию и включается осознанно
     * ключом {@code discord.yml → link.post-code-to-channel} для канала, куда
     * посторонним хода нет.
     *
     * <p>Сообщение уходит через ту же очередь, что и остальные: бот забирает его
     * по своему ключу и публикует в канал из собственной конфигурации. Отдельный
     * получатель у него отсутствует — игрок ещё не привязан, и написать ему лично
     * невозможно, именно этим привязка и начинается.
     */
    private void publishToChannel(Player player, LinkService.LinkCode code) {
        if (kind != Kind.DISCORD
                || !core.config().getBoolean(ConfigFile.DISCORD, "link.post-code-to-channel", false)) {
            return;
        }
        Instant now = Instant.now();
        core.botOutbox().append(new BotMessage(0L, BotPlatform.DISCORD, 0L, 0L,
                        BotMessage.Kind.LINK_CODE,
                        Map.of("code", code.code(),
                                "username", player.getUsername(),
                                "ttl", humanize(code.ttl())),
                        now, now.plus(code.ttl())))
                .thenRun(() -> player.sendMessage(miniMessage.deserialize(
                        "  <dark_gray>Код продублирован в канал привязки.")))
                .exceptionally(e -> {
                    // Не помеха: код уже показан в игре, и привязка возможна без
                    // канала. Сообщать игроку о сбое служебной публикации незачем.
                    logger.warn("Код привязки не опубликован в канал Discord для {}",
                            player.getUsername(), e);
                    return null;
                });
    }

    /**
     * Обезвреживает значение внутри тега MiniMessage.
     *
     * <p>Адрес приходит из конфигурации, а не от игрока, но апостроф в нём закрыл
     * бы кавычку тега и превратил остаток строки в разметку.
     */
    private static String escapeTag(String value) {
        return value.replace("'", "").replace("<", "").replace(">", "");
    }

    private void unlink(Player player, Account account) {
        CompletableFuture<OperationResult<Void>> future = kind == Kind.TELEGRAM
                ? core.links().unlinkTelegram(account.id(), contextOf(player))
                : core.links().unlinkDiscord(account.id(), contextOf(player));

        future.thenAccept(result -> player.sendMessage(result.isSuccess()
                ? prefixed("<yellow>" + kind.title + " отвязан. Подтверждение входа этим "
                        + "способом больше не работает.")
                : prefixed("<red>" + describe(result.errorCode()))));
    }

    /**
     * Включает или выключает подтверждение входа кнопкой.
     *
     * <p>Вместе с флагом привязки меняется и список методов второго фактора у
     * аккаунта — иначе вход продолжал бы проходить без подтверждения, а игрок
     * считал бы себя защищённым.
     */
    private void approval(Player player, Account account, String[] args) {
        if (args.length < 2) {
            player.sendMessage(prefixed("<yellow>Использование: <white>/"
                    + kind.command() + " approval on|off"));
            return;
        }
        boolean enabled = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on", "вкл", "true", "да" -> true;
            default -> false;
        };

        CompletableFuture<OperationResult<Void>> future = kind == Kind.TELEGRAM
                ? core.links().setTelegramLoginApproval(account.id(), enabled)
                : core.links().setDiscordLoginApproval(account.id(), enabled);

        future.thenAccept(result -> {
            if (!result.isSuccess()) {
                player.sendMessage(prefixed("<red>" + describe(result.errorCode())));
                return;
            }
            player.sendMessage(enabled
                    ? prefixed("<green>Подтверждение входа через " + kind.title + " включено. "
                            + "При входе придёт сообщение с кнопками.")
                    : prefixed("<yellow>Подтверждение входа через " + kind.title + " выключено."));
        });
    }

    private void usage(Player player) {
        player.sendMessage(prefixed("<yellow>Команды /" + kind.command() + ":"));
        player.sendMessage(miniMessage.deserialize("  <white>/" + kind.command()
                + " <gray>— состояние привязки или новый код"));
        player.sendMessage(miniMessage.deserialize("  <white>/" + kind.command()
                + " unlink <gray>— отвязать"));
        player.sendMessage(miniMessage.deserialize("  <white>/" + kind.command()
                + " approval on|off <gray>— подтверждение входа кнопкой"));
    }

    // ------------------------------------------------------------------ вспомогательное

    private CompletableFuture<OperationResult<LinkService.LinkCode>> createCode(long accountId,
                                                                               AuthContext context) {
        return kind == Kind.TELEGRAM
                ? core.links().createTelegramLinkCode(accountId, context)
                : core.links().createDiscordLinkCode(accountId, context);
    }

    /** Общий вид привязки: командам нужны только имя и флаг подтверждения. */
    private record Binding(String displayName, boolean loginApproval) {
    }

    private CompletableFuture<java.util.Optional<Binding>> findBinding(long accountId) {
        return kind == Kind.TELEGRAM
                ? core.links().findTelegram(accountId).thenApply(found -> found.map(
                        value -> new Binding(value.displayName(), value.loginApprovalEnabled())))
                : core.links().findDiscord(accountId).thenApply(found -> found.map(
                        value -> new Binding(value.displayName(), value.loginApprovalEnabled())));
    }

    private String botName() {
        String username = core.config().getString(ConfigFile.TELEGRAM, "bot.username", "");
        return username.isBlank() ? "бота сети" : "@" + username;
    }

    private static String humanize(Duration ttl) {
        long minutes = ttl.toMinutes();
        if (minutes < 1) {
            return ttl.toSeconds() + " с";
        }
        return minutes + " мин";
    }

    private static String describe(String code) {
        if (code == null) {
            return "Не удалось выполнить операцию.";
        }
        return switch (code) {
            case "NOT_LINKED" -> "Мессенджер не привязан.";
            case "ALREADY_LINKED" -> "К аккаунту уже привязан этот мессенджер.";
            case "RATE_LIMITED" -> "Слишком часто. Попробуйте позже.";
            case "DISABLED" -> "Возможность отключена администрацией.";
            default -> "Не удалось выполнить операцию.";
        };
    }

    private AuthContext contextOf(Player player) {
        return AuthContext.minecraft(
                IpAddress.of(player.getRemoteAddress().getAddress()),
                player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(null),
                player.getProtocolVersion().getProtocol(),
                player.getClientBrand());
    }

    /** Строка, собранная на месте, с общим префиксом сети. */
    private Component prefixed(String text) {
        return messages.prefixedRaw(text);
    }

    /**
     * Подсказки только для подкоманд.
     *
     * <p>Код привязки не дополняется никогда: он одноразовый и предъявительский,
     * а автодополнение показало бы его любому, кто смотрит в экран.
     */
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        String partial = Suggestions.partial(args);

        if (Suggestions.depth(args) == 1) {
            return Suggestions.matching(List.of("code", "unlink", "approval"), partial);
        }
        if (Suggestions.depth(args) == 2 && "approval".equals(Suggestions.subcommand(args))) {
            return Suggestions.matching(List.of("on", "off"), partial);
        }
        return List.of();
    }
}
