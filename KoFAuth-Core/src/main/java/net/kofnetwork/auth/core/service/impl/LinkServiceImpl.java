package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
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
import net.kofnetwork.auth.api.service.SecurityService;
import net.kofnetwork.auth.api.service.TokenService;
import net.kofnetwork.auth.core.cache.CacheProvider;
import net.kofnetwork.auth.core.security.TokenGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private final CacheProvider cache;
    private final ConfigurationService config;
    private final SecurityService security;

    public LinkServiceImpl(@NotNull TelegramRepository telegram,
                           @NotNull DiscordRepository discord,
                           @NotNull AccountRepository accounts,
                           @NotNull TokenService tokens,
                           @NotNull AuditService audit,
                           @NotNull EventBus events,
                           @NotNull CacheProvider cache,
                           @NotNull ConfigurationService config,
                           @NotNull SecurityService security) {
        this.telegram = telegram;
        this.discord = discord;
        this.accounts = accounts;
        this.tokens = tokens;
        this.audit = audit;
        this.events = events;
        this.cache = cache;
        this.config = config;
        this.security = security;
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

        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);

        return telegram.findByTelegramId(telegramId).thenCompose(existing -> {
            if (existing.isPresent()) {
                return completed(OperationResult.fail("TELEGRAM_ALREADY_LINKED",
                        "Этот Telegram уже привязан к аккаунту"));
            }
            // Код сначала проверяется, но не гасится: до вставки остаётся ещё одна
            // причина отказа — у аккаунта уже есть привязка. Гасить код перед этой
            // проверкой значило бы сжечь его впустую.
            return tokens.peek(normalized, TokenType.TELEGRAM_LINK).thenCompose(peeked -> {
                Optional<Long> accountId = accountOf(peeked);
                if (accountId.isEmpty()) {
                    return completed(OperationResult.<TelegramBinding>fail("CODE_INVALID",
                            "Код недействителен или истёк"));
                }
                return telegram.findByAccount(accountId.get()).thenCompose(own -> {
                    if (own.isPresent()) {
                        // Столбец account_id уникален: без этой проверки вставка
                        // упиралась бы в ограничение базы, и игрок получал бы
                        // внутреннюю ошибку вместо объяснения — уже потеряв код.
                        return completed(OperationResult.<TelegramBinding>fail("ALREADY_LINKED",
                                "К аккаунту уже привязан другой Telegram. Сначала отвяжите его."));
                    }
                    return consumeAndLinkTelegram(normalized, accountId.get(),
                            telegramId, chatId, context);
                });
            });
        });
    }

    private CompletableFuture<OperationResult<TelegramBinding>> consumeAndLinkTelegram(
            String code, long expectedAccountId, long telegramId, long chatId,
            AuthContext context) {

        return tokens.consume(code, TokenType.TELEGRAM_LINK, context.ip()).thenCompose(result -> {
            // Между peek и consume код мог быть погашен другим запросом: гасит
            // только consume, и только он даёт право на привязку.
            Optional<Long> accountId = accountOf(result);
            if (accountId.isEmpty() || accountId.get() != expectedAccountId) {
                return completed(OperationResult.<TelegramBinding>fail("CODE_INVALID",
                        "Код недействителен или истёк"));
            }
            return telegram.insert(TelegramBinding.create(expectedAccountId, telegramId, chatId))
                    .thenCompose(binding -> events.publish(BindingChangedEvent.of(expectedAccountId,
                                    BindingChangedEvent.BindingKind.TELEGRAM,
                                    BindingChangedEvent.Action.LINKED,
                                    binding.displayName(), context))
                            .thenCompose(ignored -> audit.log(expectedAccountId,
                                    SecurityEventType.TELEGRAM_LINKED, context,
                                    "Привязан Telegram"))
                            .thenApply(ignored -> OperationResult.ok(binding)));
        });
    }

    /** Идентификатор аккаунта из результата проверки кода, если он там есть. */
    private static Optional<Long> accountOf(
            OperationResult<net.kofnetwork.auth.api.model.AuthToken> result) {
        return result.isFailure()
                ? Optional.empty()
                : Optional.ofNullable(result.value().accountId());
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<DiscordBinding>> completeDiscordLink(
            @NotNull String code, long discordId, @NotNull AuthContext context) {

        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);

        return discord.findByDiscordId(discordId).thenCompose(existing -> {
            if (existing.isPresent()) {
                return completed(OperationResult.fail("DISCORD_ALREADY_LINKED",
                        "Этот Discord уже привязан к аккаунту"));
            }
            // Тот же порядок, что и в Telegram: проверить всё, что можно, до
            // гашения кода — он одноразовый и выдаётся только в игре.
            return tokens.peek(normalized, TokenType.DISCORD_LINK).thenCompose(peeked -> {
                Optional<Long> accountId = accountOf(peeked);
                if (accountId.isEmpty()) {
                    return completed(OperationResult.<DiscordBinding>fail("CODE_INVALID",
                            "Код недействителен или истёк"));
                }
                return discord.findByAccount(accountId.get()).thenCompose(own -> {
                    if (own.isPresent()) {
                        return completed(OperationResult.<DiscordBinding>fail("ALREADY_LINKED",
                                "К аккаунту уже привязан другой Discord. Сначала отвяжите его."));
                    }
                    return consumeAndLinkDiscord(normalized, accountId.get(), discordId, context);
                });
            });
        });
    }

    private CompletableFuture<OperationResult<DiscordBinding>> consumeAndLinkDiscord(
            String code, long expectedAccountId, long discordId, AuthContext context) {

        return tokens.consume(code, TokenType.DISCORD_LINK, context.ip()).thenCompose(result -> {
            Optional<Long> accountId = accountOf(result);
            if (accountId.isEmpty() || accountId.get() != expectedAccountId) {
                return completed(OperationResult.<DiscordBinding>fail("CODE_INVALID",
                        "Код недействителен или истёк"));
            }
            return discord.insert(DiscordBinding.create(expectedAccountId, discordId))
                    .thenCompose(binding -> events.publish(BindingChangedEvent.of(expectedAccountId,
                                    BindingChangedEvent.BindingKind.DISCORD,
                                    BindingChangedEvent.Action.LINKED,
                                    binding.displayName(), context))
                            .thenCompose(ignored -> audit.log(expectedAccountId,
                                    SecurityEventType.DISCORD_LINKED, context,
                                    "Привязан Discord"))
                            .thenApply(ignored -> OperationResult.ok(binding)));
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

    // ================================================================ привязка сайта

    /** Нижняя граница длины кода: короче подбирается даже при ограничении частоты. */
    private static final int MIN_WEB_LINK_CODE_LENGTH = 7;

    private static final String FIELD_ACCOUNT_ID = "accountId";
    private static final String FIELD_PLAYER_UUID = "playerUuid";
    private static final String FIELD_USERNAME = "username";

    @Override
    public @NotNull CompletableFuture<OperationResult<WebLinkRequest>> startWebLink(
            @NotNull AuthContext context) {

        if (!cache.isAvailable()) {
            // Запрос живёт в общем кэше: код набирают в игре, то есть в другом
            // процессе. Без Redis подтверждение не дойдёт до сайта никогда, и
            // честнее отказать сразу, чем показать код, который не сработает.
            return completed(OperationResult.fail("CACHE_UNAVAILABLE",
                    "Привязка недоступна: общий кэш выключен"));
        }

        return security.checkAndConsume("web-link.per-ip", context.ip().asString())
                .thenCompose(verdict -> verdict.allowed()
                        ? createWebLink()
                        : completed(OperationResult.<WebLinkRequest>fail("RATE_LIMITED",
                                "Слишком много запросов. Попробуйте позже")));
    }

    private CompletableFuture<OperationResult<WebLinkRequest>> createWebLink() {
        Duration ttl = webLinkTtl();
        String code = TokenGenerator.humanReadableCode(webLinkCodeLength());
        String pollToken = TokenGenerator.randomToken();

        // Две записи: по коду находим ожидание, по секрету — его состояние.
        // Хранить одно в другом нельзя: код короткий и подбираемый, а выдавать
        // по нему токены доступа — значит свести стойкость к длине кода.
        return cache.set(CacheProvider.Keys.WEB_LINK_CODE + code, pollToken, ttl)
                .thenCompose(ignored -> cache.setHash(
                        CacheProvider.Keys.WEB_LINK_POLL + pollToken,
                        Map.of(FIELD_ACCOUNT_ID, ""),
                        ttl))
                .thenApply(ignored -> OperationResult.ok(
                        new WebLinkRequest(code, pollToken, ttl)));
    }

    private Duration webLinkTtl() {
        return config.getDuration(ConfigFile.CONFIG, "web.link.ttl", Duration.ofMinutes(5));
    }

    /**
     * Длина кода привязки.
     *
     * <p>Настройка ограничена снизу: код открывает полный доступ к аккаунту,
     * и администратор, поставивший четыре символа ради удобства, не должен
     * получить подбираемый за минуты код.
     */
    private int webLinkCodeLength() {
        return Math.max(MIN_WEB_LINK_CODE_LENGTH,
                config.getInt(ConfigFile.CONFIG, "web.link.code-length", 8));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> confirmWebLink(
            @NotNull String code, @NotNull UUID playerUuid, long accountId,
            @NotNull AuthContext context) {

        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);

        // Ограничение на подбор. Считается по аккаунту, а не по адресу: код
        // вводится в игре, где игрок уже опознан, и его собственный счётчик
        // точнее общего адреса, за которым может сидеть весь интернет-провайдер.
        return security.checkAndConsume("web-link.confirm-per-account", String.valueOf(accountId))
                .thenCompose(verdict -> verdict.allowed()
                        ? consumeWebLinkCode(normalized, playerUuid, accountId, context)
                        : completed(OperationResult.<Void>fail("RATE_LIMITED",
                                "Слишком много попыток. Попробуйте позже")));
    }

    private CompletableFuture<OperationResult<Void>> consumeWebLinkCode(String normalized,
                                                                         UUID playerUuid,
                                                                         long accountId,
                                                                         AuthContext context) {
        // Код гасится чтением: повторный ввод того же кода не должен выдавать
        // доступ второму браузеру.
        return cache.getAndDelete(CacheProvider.Keys.WEB_LINK_CODE + normalized)
                .thenCompose(pollToken -> {
                    if (pollToken.isEmpty()) {
                        return completed(OperationResult.<Void>fail("CODE_INVALID",
                                "Код недействителен или истёк"));
                    }
                    return accounts.findById(accountId).thenCompose(found -> {
                        if (found.isEmpty()) {
                            return completed(OperationResult.<Void>fail("ACCOUNT_NOT_FOUND",
                                    "Аккаунт не найден"));
                        }
                        return markConfirmed(pollToken.get(), found.get(), playerUuid, context);
                    });
                });
    }

    private CompletableFuture<OperationResult<Void>> markConfirmed(String pollToken,
                                                                    Account account,
                                                                    UUID playerUuid,
                                                                    AuthContext context) {
        return cache.setHash(CacheProvider.Keys.WEB_LINK_POLL + pollToken,
                        Map.of(FIELD_ACCOUNT_ID, String.valueOf(account.id()),
                                FIELD_PLAYER_UUID, playerUuid.toString(),
                                FIELD_USERNAME, account.username()),
                        webLinkTtl())
                .thenCompose(ignored -> audit.log(account.id(),
                        SecurityEventType.LOGIN_SUCCESS, context,
                        "Сайту выдан доступ по коду привязки"))
                .thenApply(ignored -> OperationResult.<Void>ok());
    }

    @Override
    public @NotNull CompletableFuture<Optional<WebLinkConfirmation>> pollWebLink(
            @NotNull String pollToken) {

        String key = CacheProvider.Keys.WEB_LINK_POLL + pollToken;

        return cache.getHash(key).thenCompose(hash -> {
            String accountId = hash.get(FIELD_ACCOUNT_ID);
            if (accountId == null || accountId.isBlank()) {
                // Записи нет (истекла) либо игрок ещё не подтвердил. Для сайта
                // это одно и то же: продолжать опрос до истечения срока.
                return CompletableFuture.completedFuture(Optional.<WebLinkConfirmation>empty());
            }
            // Подтверждение выдаётся один раз: дальше секрет бесполезен.
            return cache.delete(key).thenApply(ignored -> parseConfirmation(hash));
        });
    }

    private static Optional<WebLinkConfirmation> parseConfirmation(Map<String, String> hash) {
        try {
            return Optional.of(new WebLinkConfirmation(
                    Long.parseLong(hash.get(FIELD_ACCOUNT_ID)),
                    UUID.fromString(hash.get(FIELD_PLAYER_UUID)),
                    hash.getOrDefault(FIELD_USERNAME, "")));
        } catch (RuntimeException e) {
            // Битая запись в кэше не должна ронять опрос: сайт получит «ещё нет»
            // и повторит, а запись всё равно уже удалена.
            LOGGER.warn("Некорректная запись подтверждения привязки сайта", e);
            return Optional.empty();
        }
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

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> setTelegramNotifications(
            long accountId, boolean enabled) {
        return telegram.findByAccount(accountId).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(OperationResult.<Void>fail("NOT_LINKED", "Telegram не привязан"));
            }
            return telegram.update(found.get().withNotifications(enabled))
                    .thenApply(ignored -> OperationResult.<Void>ok());
        });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> setDiscordNotifications(
            long accountId, boolean enabled) {
        return discord.findByAccount(accountId).thenCompose(found -> {
            if (found.isEmpty()) {
                return completed(OperationResult.<Void>fail("NOT_LINKED", "Discord не привязан"));
            }
            return discord.update(found.get().withNotifications(enabled))
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
