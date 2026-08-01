package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.repository.AccountRepository;
import net.kofnetwork.auth.api.repository.LoginHistoryRepository;
import net.kofnetwork.auth.api.service.SecurityService;
import net.kofnetwork.auth.core.cache.CacheProvider;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link SecurityService}: ограничение скорости, AntiBot, репутация адреса.
 *
 * <p>Все три механизма отвечают на один вопрос — «стоит ли вообще обрабатывать этот
 * запрос» — и вызываются в самом начале, до обращения к базе и до дорогой проверки
 * BCrypt. Порядок существенен: если считать хэш до отсечек, перебор паролей нагружает
 * процессор сервера сильнее, чем машину атакующего.
 */
public final class SecurityServiceImpl implements SecurityService {

    private final ConfigurationService config;
    private final CacheProvider cache;
    private final AccountRepository accounts;
    private final LoginHistoryRepository loginHistory;

    public SecurityServiceImpl(@NotNull ConfigurationService config,
                               @NotNull CacheProvider cache,
                               @NotNull AccountRepository accounts,
                               @NotNull LoginHistoryRepository loginHistory) {
        this.config = config;
        this.cache = cache;
        this.accounts = accounts;
        this.loginHistory = loginHistory;
    }

    // ------------------------------------------------------------------ ограничение скорости

    @Override
    public @NotNull CompletableFuture<RateLimitVerdict> checkRateLimit(@NotNull String scope,
                                                                        @NotNull String key) {
        if (!isRateLimitEnabled()) {
            return CompletableFuture.completedFuture(RateLimitVerdict.allow(Integer.MAX_VALUE));
        }
        Limit limit = limitFor(scope);
        return cache.countSlidingWindow(CacheProvider.Keys.rateLimit(scope, key), limit.window())
                .thenApply(count -> verdict(count, limit));
    }

    @Override
    public @NotNull CompletableFuture<RateLimitVerdict> checkAndConsume(@NotNull String scope,
                                                                         @NotNull String key) {
        if (!isRateLimitEnabled()) {
            return CompletableFuture.completedFuture(RateLimitVerdict.allow(Integer.MAX_VALUE));
        }
        Limit limit = limitFor(scope);
        // Проверка и учёт одной операцией: раздельные дают окно, в котором
        // параллельные запросы проходят проверку до того, как хоть один засчитан.
        return cache.incrementSlidingWindow(CacheProvider.Keys.rateLimit(scope, key), limit.window())
                .thenApply(count -> verdict(count, limit));
    }

    private static RateLimitVerdict verdict(long count, Limit limit) {
        if (count > limit.attempts()) {
            return RateLimitVerdict.deny(limit.window());
        }
        return RateLimitVerdict.allow((int) Math.max(0, limit.attempts() - count));
    }

    @Override
    public @NotNull CompletableFuture<Void> resetRateLimit(@NotNull String scope, @NotNull String key) {
        return cache.delete(CacheProvider.Keys.rateLimit(scope, key)).thenApply(ignored -> null);
    }

    private boolean isRateLimitEnabled() {
        return config.getBoolean(ConfigFile.SECURITY, "rate-limit.enabled", true);
    }

    /** Настроенный лимит для области. */
    private record Limit(int attempts, Duration window) {
    }

    /**
     * Читает лимит из конфигурации.
     *
     * <p>Область — это путь вида {@code login.per-ip}: так одна строка настройки
     * читается без {@code switch} по всем возможным областям, и добавление новой
     * не требует правки кода.
     */
    private Limit limitFor(String scope) {
        String base = "rate-limit." + scope;
        int attempts = config.getInt(ConfigFile.SECURITY, base + ".attempts", 10);
        Duration window = config.getDuration(ConfigFile.SECURITY, base + ".window",
                Duration.ofMinutes(1));
        return new Limit(attempts, window);
    }

    // ------------------------------------------------------------------ AntiBot

    @Override
    public @NotNull CompletableFuture<Boolean> isBotSuspected(@NotNull AuthContext context) {
        if (!config.getBoolean(ConfigFile.SECURITY, "antibot.enabled", true)
                || !context.isSubjectToRateLimit()
                || context.ip().isLoopbackOrPrivate()) {
            return CompletableFuture.completedFuture(false);
        }

        int maxPerSubnet = config.getInt(ConfigFile.SECURITY, "antibot.max-connections-per-subnet", 10);
        Duration subnetWindow = config.getDuration(ConfigFile.SECURITY, "antibot.subnet-window",
                Duration.ofMinutes(1));

        // Считаем по подсети, а не только по адресу: ботнет обычно распоряжается
        // диапазоном, и лимит на одиночный адрес он обходит сменой последнего октета.
        String subnetKey = "antibot:subnet:" + context.ip().subnet().asString();

        return cache.incrementSlidingWindow(subnetKey, subnetWindow)
                .thenCompose(subnetCount -> {
                    if (subnetCount > maxPerSubnet) {
                        return CompletableFuture.completedFuture(true);
                    }
                    return countRegistrationsCheck(context);
                });
    }

    /** Слишком много аккаунтов с одного адреса — признак фермы. */
    private CompletableFuture<Boolean> countRegistrationsCheck(AuthContext context) {
        int maxAccounts = config.getInt(ConfigFile.CONFIG, "auth.registration.max-accounts-per-ip", 3);
        if (maxAccounts <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        Duration window = config.getDuration(ConfigFile.CONFIG, "auth.registration.ip-limit-window",
                Duration.ofDays(7));
        return accounts.countRegistrationsFromIp(context.ip(), Instant.now().minus(window))
                // Превышение лимита регистраций само по себе не бот: у семьи за одним
                // NAT легко набирается три аккаунта. Порог удвоенный.
                .thenApply(count -> count > maxAccounts * 2L);
    }

    // ------------------------------------------------------------------ репутация адреса

    @Override
    public @NotNull CompletableFuture<IpReputation> checkIpReputation(@NotNull IpAddress ip) {
        if (!config.getBoolean(ConfigFile.SECURITY, "antivpn.enabled", false)) {
            return CompletableFuture.completedFuture(IpReputation.UNKNOWN);
        }
        if (ip.isLoopbackOrPrivate() || ip.isUnknown()) {
            return CompletableFuture.completedFuture(IpReputation.CLEAN);
        }

        String key = CacheProvider.Keys.IP_REPUTATION + ip.asString();
        return cache.get(key).thenCompose(cached -> {
            if (cached.isPresent()) {
                try {
                    return CompletableFuture.completedFuture(IpReputation.valueOf(cached.get()));
                } catch (IllegalArgumentException e) {
                    // Значение из более старой версии — перезапросим.
                }
            }
            return lookupReputation(ip).thenCompose(verdict -> {
                Duration ttl = config.getDuration(ConfigFile.DATABASE, "cache.ip-reputation-ttl",
                        Duration.ofHours(12));
                return cache.set(key, verdict.name(), ttl).thenApply(ignored -> verdict);
            });
        });
    }

    /**
     * Запрос к внешней службе репутации.
     *
     * <p>Провайдер {@code none} — единственный, реализованный в Core: обращение к
     * платному API живёт в отдельном модуле, чтобы Core не зависел от HTTP-клиента.
     * При недоступности провайдера возвращается {@link IpReputation#UNKNOWN} —
     * отказ стороннего сервиса не должен закрывать вход всей сети.
     */
    private CompletableFuture<IpReputation> lookupReputation(@NotNull IpAddress ip) {
        String provider = config.getString(ConfigFile.SECURITY, "antivpn.provider", "none");
        if ("none".equalsIgnoreCase(provider)) {
            return CompletableFuture.completedFuture(IpReputation.UNKNOWN);
        }
        // Реализация провайдера подключается извне через setReputationLookup.
        return reputationLookup == null
                ? CompletableFuture.completedFuture(IpReputation.UNKNOWN)
                : reputationLookup.apply(ip).exceptionally(e -> IpReputation.UNKNOWN);
    }

    private volatile java.util.function.Function<IpAddress, CompletableFuture<IpReputation>>
            reputationLookup;

    /**
     * Подключает внешнюю службу репутации.
     *
     * <p>Точка расширения: Core не знает ни одного провайдера, и добавление
     * нового не требует его изменения.
     */
    public void setReputationLookup(
            @NotNull java.util.function.Function<IpAddress, CompletableFuture<IpReputation>> lookup) {
        this.reputationLookup = lookup;
    }

    // ------------------------------------------------------------------ усиленная проверка

    @Override
    public @NotNull CompletableFuture<Boolean> requiresElevatedVerification(long accountId,
                                                                            @NotNull AuthContext context) {
        Duration window = Duration.ofHours(1);
        return loginHistory.countFailedSince(accountId, Instant.now().minus(window))
                .thenCompose(failures -> {
                    if (failures >= 3) {
                        return CompletableFuture.completedFuture(true);
                    }
                    if (context.country() == null) {
                        return CompletableFuture.completedFuture(false);
                    }
                    // Вход из страны, откуда этот аккаунт раньше не заходил.
                    return loginHistory
                            .hasSuccessfulLoginFromCountry(accountId, context.country())
                            .thenApply(known -> !known);
                });
    }

    // ------------------------------------------------------------------ одноразовые nonce

    @Override
    public @NotNull CompletableFuture<Boolean> consumeNonce(@NotNull String nonce) {
        // Атомарное чтение с удалением: повторное предъявление вернёт false.
        // Без атомарности двойное нажатие «Подтвердить» в Telegram создало бы
        // две сессии.
        return cache.getAndDelete(CacheProvider.Keys.NONCE + nonce)
                .thenApply(java.util.Optional::isPresent);
    }

    @Override
    public @NotNull CompletableFuture<Void> issueNonce(@NotNull String nonce, @NotNull Duration ttl) {
        return cache.set(CacheProvider.Keys.NONCE + nonce, "1", ttl);
    }
}
