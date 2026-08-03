package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.Account;
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
import java.util.List;
import java.util.Locale;
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

    private void issueCode(Player player, Account account) {
        createCode(account.id(), contextOf(player)).thenAccept(result -> {
            if (!result.isSuccess()) {
                player.sendMessage(prefixed("<red>" + describe(result.errorCode())));
                return;
            }
            LinkService.LinkCode code = result.value();
            player.sendMessage(prefixed("<green>Код привязки: <white><bold>" + code.code()));

            String where = kind == Kind.TELEGRAM
                    ? "боту " + botName()
                    : "боту на сервере Discord";
            player.sendMessage(miniMessage.deserialize(
                    "  <gray>Отправьте <white>/link " + code.code() + " <gray>" + where));
            player.sendMessage(miniMessage.deserialize(
                    "  <gray>Код действует " + humanize(code.ttl()) + " и сгорает после первого использования."));
        });
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

    private Component prefixed(String text) {
        return messages.parse(core.config().getString(
                ConfigFile.CONFIG, "messages.prefix", "") + text);
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
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("unlink", "approval").stream()
                    .filter(name -> name.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && "approval".equalsIgnoreCase(args[0])) {
            return List.of("on", "off").stream()
                    .filter(name -> name.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
