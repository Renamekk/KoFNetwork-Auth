package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.BindingChangedEvent;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.DiscordBinding;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.TelegramBinding;
import net.kofnetwork.auth.api.model.TokenType;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import net.kofnetwork.auth.api.repository.AccountRepository;
import net.kofnetwork.auth.api.repository.DiscordRepository;
import net.kofnetwork.auth.api.repository.TelegramRepository;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.api.service.AuditService;
import net.kofnetwork.auth.api.service.LinkService;
import net.kofnetwork.auth.api.service.TokenService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link LinkService}.
 *
 * <p><b>Направление привязки.</b> Код всегда выдаётся в игре и вводится в мессенджере.
 * Обратный порядок сломал бы модель доверия: если код выдаёт бот по нику, любой
 * знающий чужой ник получит код и привяжет к себе чужой аккаунт. Доказать владение
 * игровым аккаунтом можно только внутри игры.
 */
public final class LinkServiceImpl implements LinkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinkServiceImpl.class);

    private final TelegramRepository telegram;
    private final DiscordRepository discord;
    private final AccountRepository accounts;
    private final TokenService tokens;
    private final AuditService audit;
    private final EventBus events;

    public LinkServiceImpl(@NotNull TelegramRepository telegram,
                           @NotNull DiscordRepository discord,
                           @NotNull AccountRepository accounts,
                           @NotNull TokenService tokens,
                           @NotNull AuditService audit,
                           @NotNull EventBus events) {
        this.telegram = telegram;
        this.discord = discord;
        this.accounts = accounts;
        this.tokens = tokens;
        this.audit = audit;
        this.events = events;
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<LinkCode>> createTelegramLinkCode(
            long accountId, @NotNull AuthContext context) {
        return createCode(accountId, TokenType.TELEGRAM_LINK, context);
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<LinkCode>> createDiscordLinkCode(
            long accountId, @NotNull AuthContext context) {
        return createCode(accountId, TokenType.DISCORD_LINK, context);
    }

    private CompletableFuture<OperationResult<LinkCode>> createCode(long accountId,
                                                                    TokenType type,
                                                                    AuthContext context) {
        return tokens.issue(accountId, type, context.ip(), null)
                .thenApply(issued -> OperationResult.ok(
                        new LinkCode(issued.value(), type.defaultLifetime())));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<TelegramBinding>> completeTelegramLink(
            @NotNull String code, long telegramId, long chatId, @NotNull AuthContext context) {

        return telegram.findByTelegramId(telegramId).thenCompose(existing -> {
            if (existing.isPresent()) {
                return completed(OperationResult.fail("TELEGRAM_ALREADY_LINKED",
                        "Этот Telegram уже привязан к аккаунту"));
            }
            return tokens.consume(code.trim().toUpperCase(java.util.Locale.ROOT),
                    TokenType.TELEGRAM_LINK, context.ip()).thenCompose(result -> {
                if (result.isFailure()) {
                    return completed(OperationResult.<TelegramBinding>fail("CODE_INVALID",
                            "Код недействителен или истёк"));
                }
                Long accountId = result.value().accountId();
                if (accountId == null) {
                    return completed(OperationResult.<TelegramBinding>fail("CODE_INVALID",
                            "Код не связан с аккаунтом"));
                }
                return telegram.insert(TelegramBinding.create(accountId, telegramId, chatId))
                        .thenCompose(binding -> events.publish(BindingChangedEvent.of(accountId,
                                        BindingChangedEvent.BindingKind.TELEGRAM,
                                        BindingChangedEvent.Action.LINKED,
                                        binding.displayName(), context))
                                .thenCompose(ignored -> audit.log(accountId,
                                        SecurityEventType.TELEGRAM_LINKED, context,
                                        "Привязан Telegram"))
                                .thenApply(ignored -> OperationResult.ok(binding)));
            });
        });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<DiscordBinding>> completeDiscordLink(
            @NotNull String code, long discordId, @NotNull AuthContext context) {

        return discord.findByDiscordId(discordId).thenCompose(existing -> {
            if (existing.isPresent()) {
                return completed(OperationResult.fail("DISCORD_ALREADY_LINKED",
                        "Этот Discord уже привязан к аккаунту"));
            }
            return tokens.consume(code.trim().toUpperCase(java.util.Locale.ROOT),
                    TokenType.DISCORD_LINK, context.ip()).thenCompose(result -> {
                if (result.isFailure()) {
                    return completed(OperationResult.<DiscordBinding>fail("CODE_INVALID",
                            "Код недействителен или истёк"));
                }
                Long accountId = result.value().accountId();
                if (accountId == null) {
                    return completed(OperationResult.<DiscordBinding>fail("CODE_INVALID",
                            "Код не связан с аккаунтом"));
                }
                return discord.insert(DiscordBinding.create(accountId, discordId))
                        .thenCompose(binding -> events.publish(BindingChangedEvent.of(accountId,
                                        BindingChangedEvent.BindingKind.DISCORD,
                                        BindingChangedEvent.Action.LINKED,
                                        binding.displayName(), context))
                                .thenCompose(ignored -> audit.log(accountId,
                                        SecurityEventType.DISCORD_LINKED, context,
                                        "Привязан Discord"))
                                .thenApply(ignored -> OperationResult.ok(binding)));
            });
        });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<DiscordBinding>> linkVerifiedDiscord(
            long accountId, long discordId, @Nullable String username,
            @NotNull AuthContext context) {

        // Порядок проверок тот же, что при привязке по коду: сначала «этот Discord
        // уже занят», потом «у аккаунта уже есть привязка». Две проверки, а не одна:
        // уникальность в базе на discord_id ловит первый случай, но не второй, и без
        // явной проверки владелец молча переписал бы себе чужую строку.
        return discord.findByDiscordId(discordId).thenCompose(existing -> {
            if (existing.isPresent()) {
                return completed(OperationResult.fail(
                        existing.get().accountId() == accountId
                                ? "ALREADY_LINKED" : "DISCORD_ALREADY_LINKED",
                        existing.get().accountId() == accountId
                                ? "Этот Discord уже привязан к вашему аккаунту"
                                : "Этот Discord уже привязан к другому аккаунту"));
            }
            return discord.findByAccount(accountId).thenCompose(own -> {
                if (own.isPresent()) {
                    return completed(OperationResult.<DiscordBinding>fail("ALREADY_LINKED",
                            "К аккаунту уже привязан другой Discord"));
                }
                DiscordBinding fresh = DiscordBinding.create(accountId, discordId)
                        .withProfile(username, null, null, null);

                return discord.insert(fresh)
                        .thenCompose(binding -> events.publish(BindingChangedEvent.of(accountId,
                                        BindingChangedEvent.BindingKind.DISCORD,
                                        BindingChangedEvent.Action.LINKED,
                                        binding.displayName(), context))
                                .thenCompose(ignored -> audit.log(accountId,
                                        SecurityEventType.DISCORD_LINKED, context,
                                        "Привязан Discord через OAuth2"))
                                .thenApply(ignored -> OperationResult.ok(binding)));
            });
        });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> unlinkTelegram(
            long accountId, @NotNull AuthContext context) {
        return telegram.deleteByAccount(accountId)
                .thenCompose(deleted -> deleted
                        ? removeTwoFactor(accountId, TwoFactorMethod.TELEGRAM)
                                .thenCompose(ignored -> events.publish(BindingChangedEvent.of(
                                        accountId, BindingChangedEvent.BindingKind.TELEGRAM,
                                        BindingChangedEvent.Action.UNLINKED, null, context)))
                                .thenCompose(ignored -> audit.log(accountId,
                                        SecurityEventType.TELEGRAM_UNLINKED, context,
                                        "Telegram отвязан"))
                                .thenApply(ignored -> OperationResult.<Void>ok())
                        : completed(OperationResult.<Void>fail("NOT_LINKED",
                                "Telegram не привязан")));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> unlinkDiscord(
            long accountId, @NotNull AuthContext context) {
        return discord.deleteByAccount(accountId)
                .thenCompose(deleted -> deleted
                        ? removeTwoFactor(accountId, TwoFactorMethod.DISCORD)
                                .thenCompose(ignored -> events.publish(BindingChangedEvent.of(
                                        accountId, BindingChangedEvent.BindingKind.DISCORD,
                                        BindingChangedEvent.Action.UNLINKED, null, context)))
                                .thenCompose(ignored -> audit.log(accountId,
                                        SecurityEventType.DISCORD_UNLINKED, context,
                                        "Discord отвязан"))
                                .thenApply(ignored -> OperationResult.<Void>ok())
                        : completed(OperationResult.<Void>fail("NOT_LINKED",
                                "Discord не привязан")));
    }

    /**
     * Убирает второй фактор, ставший недоступным.
     *
     * <p>Обязательно: аккаунт с включённым подтверждением через Telegram и без
     * привязанного Telegram оказался бы заперт навсегда.
     */
    private CompletableFuture<Void> removeTwoFactor(long accountId, TwoFactorMethod method) {
        return accounts.findById(accountId).thenCompose(found -> {
            if (found.isEmpty() || !found.get().hasTwoFactor(method)) {
                return CompletableFuture.completedFuture(null);
            }
            Set<TwoFactorMethod> methods = EnumSet.noneOf(TwoFactorMethod.class);
            methods.addAll(found.get().twoFactorMethods());
            methods.remove(method);
            return accounts.updateTwoFactorMethods(accountId, methods);
        });
    }

    @Override
    public @NotNull CompletableFuture<Optional<TelegramBinding>> findTelegram(long accountId) {
        return telegram.findByAccount(accountId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<DiscordBinding>> findDiscord(long accountId) {
        return discord.findByAccount(accountId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<Long>> findAccountByTelegramId(long telegramId) {
        return telegram.findByTelegramId(telegramId)
                .thenApply(binding -> binding.map(TelegramBinding::accountId));
    }

    @Override
    public @NotNull CompletableFuture<Optional<Long>> findAccountByDiscordId(long discordId) {
        return discord.findByDiscordId(discordId)
                .thenApply(binding -> binding.map(DiscordBinding::accountId));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> setTelegramLoginApproval(
            long accountId, boolean enabled) {
        return telegram.findByAccount(accountId).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(OperationResult.<Void>fail("NOT_LINKED", "Telegram не привязан"));
            }
            return telegram.update(found.get().withLoginApproval(enabled))
                    .thenCompose(ignored -> toggleTwoFactor(accountId,
                            TwoFactorMethod.TELEGRAM, enabled))
                    .thenApply(ignored -> OperationResult.<Void>ok());
        });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> setDiscordLoginApproval(
            long accountId, boolean enabled) {
        return discord.findByAccount(accountId).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(OperationResult.<Void>fail("NOT_LINKED", "Discord не привязан"));
            }
            return discord.update(found.get().withLoginApproval(enabled))
                    .thenCompose(ignored -> toggleTwoFactor(accountId,
                            TwoFactorMethod.DISCORD, enabled))
                    .thenApply(ignored -> OperationResult.<Void>ok());
        });
    }

    private CompletableFuture<Void> toggleTwoFactor(long accountId,
                                                    TwoFactorMethod method,
                                                    boolean enabled) {
        return accounts.findById(accountId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            Account account = found.get();
            Set<TwoFactorMethod> methods = EnumSet.noneOf(TwoFactorMethod.class);
            methods.addAll(account.twoFactorMethods());
            if (enabled) {
                methods.add(method);
            } else {
                methods.remove(method);
            }
            return accounts.updateTwoFactorMethods(accountId, methods);
        });
    }

    private static <T> CompletableFuture<OperationResult<T>> completed(OperationResult<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
