package net.kofnetwork.auth.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.core.KoFAuthCore;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Discord-бот KoF Network на slash-командах.
 *
 * <p>Поверхность та же, что у Telegram-бота: привязка, профиль, устройства,
 * история, подтверждение входа кнопкой. Разница только в оформлении — Discord
 * показывает embed'ы вместо HTML.
 *
 * <p><b>Все ответы эфемерные.</b> {@code setEphemeral(true)} означает, что
 * сообщение видит только вызвавший. Без этого история входов и список устройств
 * оказались бы в общем канале сервера.
 */
public final class KoFAuthDiscordBot extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(KoFAuthDiscordBot.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private static final Color ACCENT = new Color(0x5B, 0x6B, 0xFF);
    private static final Color DANGER = new Color(0xFF, 0x5F, 0x56);

    private static final String APPROVE = "kofauth:approve:";
    private static final String DENY = "kofauth:deny:";

    private final KoFAuthCore core;

    public KoFAuthDiscordBot(KoFAuthCore core) {
        this.core = core;
    }

    /** Описание slash-команд для регистрации в Discord. */
    public static List<CommandData> commands() {
        return List.of(
                Commands.slash("link", "Привязать игровой аккаунт")
                        .addOption(OptionType.STRING, "код", "Код, полученный в игре", true),
                Commands.slash("unlink", "Отвязать игровой аккаунт"),
                Commands.slash("profile", "Сведения об аккаунте"),
                Commands.slash("devices", "Известные устройства"),
                Commands.slash("history", "История входов"),
                Commands.slash("security", "Состояние защиты"),
                Commands.slash("login", "Как работает подтверждение входа"));
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        // Ответ откладываем: обращение к базе занимает больше трёх секунд,
        // отведённых Discord на немедленный ответ.
        event.deferReply(true).queue();
        long discordId = event.getUser().getIdLong();

        try {
            switch (event.getName()) {
                case "link" -> link(event, discordId);
                case "unlink" -> withAccount(event, discordId, this::unlink);
                case "profile" -> withAccount(event, discordId, this::profile);
                case "devices" -> withAccount(event, discordId, this::devices);
                case "history" -> withAccount(event, discordId, this::history);
                case "security" -> withAccount(event, discordId, this::security);
                case "login" -> reply(event, info("Подтверждение входа",
                        "Запрос приходит сюда автоматически при входе в игру, "
                                + "если включено подтверждение. Отдельная команда не нужна."));
                default -> reply(event, error("Неизвестная команда"));
            }
        } catch (RuntimeException e) {
            LOGGER.error("Ошибка slash-команды {}", event.getName(), e);
            reply(event, error("Внутренняя ошибка, попробуйте позже"));
        }
    }

    // ------------------------------------------------------------------ команды

    private void link(SlashCommandInteractionEvent event, long discordId) {
        var option = event.getOption("код");
        String code = option == null ? "" : option.getAsString().trim();

        core.links().completeDiscordLink(code, discordId, AuthContext.discord())
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        reply(event, info("Аккаунт привязан",
                                "Подтверждение входа можно включить в личном кабинете."));
                        return;
                    }
                    reply(event, error(switch (result.errorCode() == null ? "" : result.errorCode()) {
                        case "DISCORD_ALREADY_LINKED" ->
                                "Этот Discord уже привязан к другому аккаунту.";
                        case "CODE_INVALID" -> "Код недействителен или истёк.";
                        default -> "Не удалось привязать аккаунт.";
                    }));
                });
    }

    private void unlink(SlashCommandInteractionEvent event, Account account) {
        core.links().unlinkDiscord(account.id(), AuthContext.discord())
                .thenAccept(result -> reply(event, result.isSuccess()
                        ? info("Аккаунт отвязан", "Подтверждение входа через Discord отключено.")
                        : error("Не удалось отвязать аккаунт")));
    }

    private void profile(SlashCommandInteractionEvent event, Account account) {
        core.totp().isEnabled(account.id()).thenAccept(totp -> {
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(account.username())
                    .setColor(ACCENT)
                    .addField("Статус", String.valueOf(account.status()), true)
                    .addField("Второй фактор", totp ? "TOTP включён"
                            : (account.hasTwoFactor()
                                    ? account.twoFactorMethods().toString() : "выключен"), true)
                    .addField("Регистрация", TIME.format(account.registrationDate()), false)
                    .addField("Последний вход", account.lastLoginAt() == null
                            ? "никогда" : TIME.format(account.lastLoginAt()), false)
                    .addField("Адрес", account.lastLoginIp() == null
                            ? "—" : account.lastLoginIp().asMasked(), true)
                    .addField("Расположение",
                            location(account.lastCountry(), account.lastCity()), true);
            reply(event, embed.build());
        });
    }

    private void devices(SlashCommandInteractionEvent event, Account account) {
        core.adminOperations().listDevices(account.id()).thenAccept(devices -> {
            if (devices.isEmpty()) {
                reply(event, info("Устройства", "Записей пока нет."));
                return;
            }
            StringBuilder text = new StringBuilder();
            devices.stream().limit(10).forEach(device -> text
                    .append("• **").append(device.friendlyName()).append("**\n")
                    .append(device.lastSeenIp().asMasked())
                    .append(" · ").append(TIME.format(device.lastSeenAt()))
                    .append(device.trusted() ? " · доверенное" : "")
                    .append(device.blocked() ? " · заблокировано" : "")
                    .append("\n"));
            reply(event, info("Устройства", text.toString()));
        });
    }

    private void history(SlashCommandInteractionEvent event, Account account) {
        core.audit().getLoginHistory(account.id(), 10, 0).thenAccept(history -> {
            if (history.isEmpty()) {
                reply(event, info("История входов", "Записей пока нет."));
                return;
            }
            StringBuilder text = new StringBuilder();
            history.forEach(entry -> text
                    .append(entry.success() ? "✅" : "❌").append(' ')
                    .append(TIME.format(entry.at())).append('\n')
                    .append(entry.ipMasked())
                    .append(" · ").append(location(entry.country(), entry.city()))
                    .append(entry.success() ? "" : " · " + entry.result())
                    .append("\n\n"));
            reply(event, info("История входов", text.toString()));
        });
    }

    private void security(SlashCommandInteractionEvent event, Account account) {
        core.links().findDiscord(account.id()).thenAccept(binding -> reply(event,
                info("Защита аккаунта", """
                        Подтверждение входа: %s
                        Уведомления: %s

                        Управление — в личном кабинете."""
                        .formatted(
                                binding.map(b -> b.loginApprovalEnabled()).orElse(false)
                                        ? "включено" : "выключено",
                                binding.map(b -> b.notificationsEnabled()).orElse(false)
                                        ? "включены" : "выключены"))));
    }

    // ------------------------------------------------------------------ подтверждение входа

    /** Кнопки для запроса подтверждения входа. */
    public static List<Button> approvalButtons(String approvalToken) {
        return List.of(
                Button.success(APPROVE + approvalToken, "Это я"),
                Button.danger(DENY + approvalToken, "Это не я"));
    }

    /** Embed запроса подтверждения. */
    public static MessageEmbed approvalEmbed(String username, String ipMasked, String location) {
        return new EmbedBuilder()
                .setTitle("Вход в аккаунт " + username)
                .setColor(ACCENT)
                .setDescription("Подтвердите, что это вы.")
                .addField("Адрес", ipMasked, true)
                .addField("Расположение", location, true)
                .build();
    }

    /**
     * Embed уведомления без кнопок.
     *
     * @param detail дополнительная строка; пустая не выводится
     */
    public static MessageEmbed approvalEmbedLike(String title, String subject,
                                                 String ipMasked, String detail) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(title)
                .setColor(DANGER)
                .addField("Аккаунт", subject, true)
                .addField("Адрес", ipMasked, true);
        if (detail != null && !detail.isBlank()) {
            embed.addField("Подробности", detail, false);
        }
        embed.setFooter("Если это были не вы — смените пароль.");
        return embed.build();
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(APPROVE) && !id.startsWith(DENY)) {
            return;
        }
        event.deferEdit().queue();

        boolean approve = id.startsWith(APPROVE);
        String token = id.substring(approve ? APPROVE.length() : DENY.length());

        core.authentication().completeApproval(token, approve, AuthContext.discord())
                .thenAccept(result -> {
                    String text = approve
                            ? (result.isSuccess() ? "Вход подтверждён."
                                    : "Запрос устарел или уже обработан.")
                            : "Вход отклонён. Если это были не вы — смените пароль.";
                    // Кнопки убираем: повторно нажимать уже нечего, токен погашен.
                    event.getHook().editOriginalComponents()
                            .setEmbeds(new EmbedBuilder()
                                    .setColor(approve && result.isSuccess() ? ACCENT : DANGER)
                                    .setDescription(text)
                                    .build())
                            .queue();
                });
    }

    // ------------------------------------------------------------------ вспомогательное

    private void withAccount(SlashCommandInteractionEvent event, long discordId,
                             java.util.function.BiConsumer<SlashCommandInteractionEvent,
                                     Account> action) {
        core.links().findAccountByDiscordId(discordId)
                .thenCompose(accountId -> accountId
                        .map(core.adminOperations()::findAccount)
                        .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())))
                .thenAccept(account -> {
                    if (account.isEmpty()) {
                        reply(event, error("Аккаунт не привязан. Возьмите код в игре "
                                + "и выполните `/link код`."));
                        return;
                    }
                    action.accept(event, account.get());
                })
                .exceptionally(e -> {
                    LOGGER.error("Ошибка команды Discord", e);
                    reply(event, error("Внутренняя ошибка, попробуйте позже"));
                    return null;
                });
    }

    private static MessageEmbed info(String title, String description) {
        return new EmbedBuilder().setTitle(title).setDescription(description)
                .setColor(ACCENT).build();
    }

    private static MessageEmbed error(String description) {
        return new EmbedBuilder().setDescription(description).setColor(DANGER).build();
    }

    private static String location(String country, String city) {
        if (country == null && city == null) {
            return "неизвестно";
        }
        return city == null ? country : (country == null ? city : country + ", " + city);
    }

    private void reply(SlashCommandInteractionEvent event, MessageEmbed embed) {
        event.getHook().sendMessageEmbeds(embed).setEphemeral(true).queue(
                success -> { },
                failure -> LOGGER.warn("Не удалось ответить в Discord: {}", failure.getMessage()));
    }
}
