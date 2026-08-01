package net.kofnetwork.auth.core;

import net.kofnetwork.auth.api.KoFAuth;
import net.kofnetwork.auth.api.KoFAuthProvider;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.exception.ConfigurationException;
import net.kofnetwork.auth.api.service.AuditService;
import net.kofnetwork.auth.api.service.AuthenticationService;
import net.kofnetwork.auth.api.service.CaptchaService;
import net.kofnetwork.auth.api.service.EmailService;
import net.kofnetwork.auth.api.service.LinkService;
import net.kofnetwork.auth.api.service.RegistrationService;
import net.kofnetwork.auth.api.service.SecurityService;
import net.kofnetwork.auth.api.service.SessionService;
import net.kofnetwork.auth.api.service.TokenService;
import net.kofnetwork.auth.api.service.TotpService;
import net.kofnetwork.auth.core.cache.CacheProvider;
import net.kofnetwork.auth.core.cache.NoopCacheProvider;
import net.kofnetwork.auth.core.cache.RedisCacheProvider;
import net.kofnetwork.auth.core.concurrent.AsyncExecutors;
import net.kofnetwork.auth.core.config.YamlConfigurationService;
import net.kofnetwork.auth.core.database.DatabaseManager;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.event.RedisEventBridge;
import net.kofnetwork.auth.core.event.SimpleEventBus;
import net.kofnetwork.auth.core.mail.MailTemplateEngine;
import net.kofnetwork.auth.core.mail.SmtpMailSender;
import net.kofnetwork.auth.core.notify.EmailNotificationListener;
import net.kofnetwork.auth.core.repository.jdbc.JdbcAccountRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcCaptchaRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcDeviceRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcDiscordRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcEmailRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcLoginHistoryRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcRoleRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcSecurityLogRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcServerRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcSessionRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcSettingsRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcTelegramRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcTokenRepository;
import net.kofnetwork.auth.core.repository.jdbc.JdbcTotpRepository;
import net.kofnetwork.auth.core.security.AesCipher;
import net.kofnetwork.auth.core.security.JwtProvider;
import net.kofnetwork.auth.core.security.PasswordHasher;
import net.kofnetwork.auth.core.security.PasswordPolicy;
import net.kofnetwork.auth.core.service.impl.AuditServiceImpl;
import net.kofnetwork.auth.core.service.impl.AuthenticationServiceImpl;
import net.kofnetwork.auth.core.service.impl.CaptchaServiceImpl;
import net.kofnetwork.auth.core.service.impl.EmailServiceImpl;
import net.kofnetwork.auth.core.service.impl.LinkServiceImpl;
import net.kofnetwork.auth.core.service.impl.RegistrationServiceImpl;
import net.kofnetwork.auth.core.service.impl.SecurityServiceImpl;
import net.kofnetwork.auth.core.service.impl.SessionServiceImpl;
import net.kofnetwork.auth.core.service.impl.TokenServiceImpl;
import net.kofnetwork.auth.core.service.impl.TotpServiceImpl;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Композиционный корень KoFAuth: собирает граф зависимостей и владеет жизненным циклом.
 *
 * <p><b>Почему сборка вручную, а не DI-контейнер.</b> Guice или Spring в плагине
 * Minecraft — это лишние мегабайты в shaded jar, конфликты classpath с другими
 * плагинами и рефлексия там, где хватает двадцати строк конструкторов. Порядок
 * создания здесь виден целиком и проверяется компилятором: забыть зависимость
 * невозможно, а цикл обнаружится как невозможность написать код.
 *
 * <p>Порядок обязателен: конфигурация → пулы → база → кэш → шина → репозитории →
 * сервисы. Каждый следующий слой опирается на предыдущий.
 */
public final class KoFAuthCore implements KoFAuth, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(KoFAuthCore.class);

    private static final String VERSION = "1.0.0-SNAPSHOT";

    private final YamlConfigurationService config;
    private final AsyncExecutors executors;
    private final DatabaseManager database;
    private final CacheProvider cache;
    private final SimpleEventBus localBus;
    private final RedisEventBridge eventBus;

    private final AuthenticationService authentication;
    private final RegistrationService registration;
    private final SessionService sessions;
    private final SecurityService security;
    private final CaptchaService captcha;
    private final EmailService email;
    private final TotpService totp;
    private final LinkService links;
    private final TokenService tokens;
    private final AuditServiceImpl audit;
    private final AdminOperations adminOperations;
    private final EmailNotificationListener emailNotifications;

    private volatile boolean ready;

    private KoFAuthCore(Path configDirectory) {
        // ---- 1. Конфигурация: всё остальное читает настройки отсюда.
        this.config = new YamlConfigurationService(configDirectory,
                Runnable::run);
        this.config.initialize();

        // ---- 2. Пулы. Размер пула базы совпадает с пулом Hikari: больше потоков,
        //         чем соединений, не ускорит ни один запрос.
        int poolSize = config.getInt(ConfigFile.DATABASE, "mysql.pool.maximum-size", 10);
        this.executors = new AsyncExecutors(poolSize);

        // ---- 3. База и миграции.
        this.database = new DatabaseManager(config, executors.io());
        this.database.start();
        config.registerReloadable(database);
        SqlExecutor sql = new SqlExecutor(database, executors);

        // ---- 4. Кэш. Отсутствие Redis — не отказ: подставляется заглушка,
        //         и система продолжает работать, потеряв межпроцессную связность.
        this.cache = createCache();

        // ---- 5. Шина событий.
        this.localBus = new SimpleEventBus(executors.io());
        this.eventBus = new RedisEventBridge(localBus, cache);
        this.eventBus.start();

        // ---- 6. Криптография.
        AesCipher cipher = AesCipher.fromBase64(requiredSecret("security.encryption.key",
                config.getString(ConfigFile.SECURITY, "encryption.key", "")));
        PasswordHasher hasher = new PasswordHasher(
                config.getInt(ConfigFile.SECURITY, "password.bcrypt-cost", 12));
        PasswordPolicy policy = buildPolicy();
        JwtProvider jwt = new JwtProvider(
                requiredSecret("security.jwt.secret",
                        config.getString(ConfigFile.SECURITY, "jwt.secret", "")),
                config.getDuration(ConfigFile.SECURITY, "jwt.access-lifetime", Duration.ofMinutes(15)));

        // ---- 7. Репозитории.
        var accountRepo = new JdbcAccountRepository(sql);
        var sessionRepo = new JdbcSessionRepository(sql);
        var deviceRepo = new JdbcDeviceRepository(sql);
        var tokenRepo = new JdbcTokenRepository(sql);
        var historyRepo = new JdbcLoginHistoryRepository(sql);
        var auditRepo = new JdbcSecurityLogRepository(sql);
        var emailRepo = new JdbcEmailRepository(sql);
        var telegramRepo = new JdbcTelegramRepository(sql);
        var discordRepo = new JdbcDiscordRepository(sql, cipher);
        var totpRepo = new JdbcTotpRepository(sql, cipher);
        var captchaRepo = new JdbcCaptchaRepository(sql);
        var roleRepo = new JdbcRoleRepository(sql);
        var settingsRepo = new JdbcSettingsRepository(sql);
        var serverRepo = new JdbcServerRepository(sql);

        // ---- 8. Сервисы. Порядок продиктован зависимостями между ними.
        this.audit = new AuditServiceImpl(auditRepo, historyRepo, executors.scheduler());
        this.security = new SecurityServiceImpl(config, cache, accountRepo, historyRepo);
        this.sessions = new SessionServiceImpl(sessionRepo, deviceRepo, cache, config, eventBus);
        this.tokens = new TokenServiceImpl(tokenRepo, sessionRepo, accountRepo, jwt, config, eventBus);
        this.totp = new TotpServiceImpl(totpRepo, tokenRepo, accountRepo, audit, eventBus,
                config.getString(ConfigFile.CONFIG, "network-name", "KoF Network"));
        this.captcha = new CaptchaServiceImpl(captchaRepo, accountRepo, security, audit, config);

        SmtpMailSender mailSender = new SmtpMailSender(config);
        MailTemplateEngine mailTemplates = new MailTemplateEngine(config);
        this.email = new EmailServiceImpl(emailRepo, tokens, security, audit, mailSender,
                mailTemplates, config, executors, eventBus);

        this.links = new LinkServiceImpl(telegramRepo, discordRepo, accountRepo, tokens,
                audit, eventBus);

        this.registration = new RegistrationServiceImpl(accountRepo, roleRepo, settingsRepo,
                sessions, security, audit, hasher, policy, config, eventBus);

        this.authentication = new AuthenticationServiceImpl(accountRepo, deviceRepo, historyRepo,
                sessions, security, totp, tokens, email, audit, hasher, policy, config, eventBus);

        this.adminOperations = new AdminOperations(accountRepo, deviceRepo, roleRepo, settingsRepo);

        // Уведомления подключаются подписчиком, а не вызовами из сервисов:
        // сервис публикует «игрок вошёл» и на этом заканчивает.
        this.emailNotifications = new EmailNotificationListener(email, config);
        this.emailNotifications.register(eventBus);

        // ---- 9. Фоновое обслуживание.
        scheduleMaintenance(sessionRepo, tokenRepo, captchaRepo, auditRepo, deviceRepo, roleRepo);

        this.ready = true;
        LOGGER.info("KoFAuth {} запущен (кэш: {})", VERSION, cache.providerName());
    }

    /**
     * Читает обязательный секрет.
     *
     * <p>Пустое значение — остановка на старте, а не предупреждение в логе. Сервер,
     * поднявшийся с пустым ключом AES или JWT, опаснее сервера, который не поднялся:
     * первый молча раздаёт подделываемые токены.
     */
    private static String requiredSecret(String path, String value) {
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(
                    "Не задан обязательный секрет " + path + ". Укажите его в security.yml "
                            + "или через переменную окружения KOFAUTH_"
                            + path.replace('.', '_').toUpperCase(java.util.Locale.ROOT) + ".");
        }
        return value;
    }

    private CacheProvider createCache() {
        if (!config.getBoolean(ConfigFile.DATABASE, "redis.enabled", true)) {
            LOGGER.info("Redis отключён конфигурацией: сессии и события останутся "
                    + "в пределах процесса");
            return new NoopCacheProvider();
        }
        try {
            return RedisCacheProvider.connect(config);
        } catch (RuntimeException e) {
            // Redis настроен, но недоступен. Останавливать сеть из-за кэша нельзя:
            // деградируем и продолжаем работать по MySQL.
            LOGGER.error("Redis недоступен, работаем без кэша. Сессии не будут общими "
                    + "между процессами.", e);
            return new NoopCacheProvider();
        }
    }

    private PasswordPolicy buildPolicy() {
        return PasswordPolicy.builder()
                .minLength(config.getInt(ConfigFile.SECURITY, "password.policy.min-length", 8))
                .maxLength(config.getInt(ConfigFile.SECURITY, "password.policy.max-length", 64))
                .requireUppercase(config.getBoolean(ConfigFile.SECURITY,
                        "password.policy.require-uppercase", true))
                .requireLowercase(config.getBoolean(ConfigFile.SECURITY,
                        "password.policy.require-lowercase", true))
                .requireDigit(config.getBoolean(ConfigFile.SECURITY,
                        "password.policy.require-digit", true))
                .requireSpecial(config.getBoolean(ConfigFile.SECURITY,
                        "password.policy.require-special", false))
                .forbidUsername(config.getBoolean(ConfigFile.SECURITY,
                        "password.policy.forbid-username", true))
                .forbidCommon(config.getBoolean(ConfigFile.SECURITY,
                        "password.policy.forbid-common", true))
                .forbidSequences(config.getBoolean(ConfigFile.SECURITY,
                        "password.policy.forbid-sequences", true))
                .build();
    }

    /** Периодическая чистка. Без неё таблицы токенов и аудита растут неограниченно. */
    private void scheduleMaintenance(JdbcSessionRepository sessionRepo,
                                     JdbcTokenRepository tokenRepo,
                                     JdbcCaptchaRepository captchaRepo,
                                     JdbcSecurityLogRepository auditRepo,
                                     JdbcDeviceRepository deviceRepo,
                                     JdbcRoleRepository roleRepo) {
        Duration interval = config.getDuration(ConfigFile.CONFIG,
                "maintenance.cleanup-interval", Duration.ofHours(1));
        Duration auditRetention = config.getDuration(ConfigFile.CONFIG,
                "maintenance.audit-retention", Duration.ofDays(90));
        Duration deviceRetention = config.getDuration(ConfigFile.CONFIG,
                "maintenance.device-retention", Duration.ofDays(180));

        executors.scheduler().scheduleWithFixedDelay(() -> {
            java.time.Instant now = java.time.Instant.now();
            try {
                sessionRepo.revokeExpired(now).join();
                tokenRepo.deleteExpiredAndUsed(now).join();
                captchaRepo.expireOverdue(now).join();
                captchaRepo.deleteResolvedBefore(now.minus(Duration.ofDays(1))).join();
                auditRepo.deleteInfoBefore(now.minus(auditRetention)).join();
                deviceRepo.deleteStale(now.minus(deviceRetention)).join();
                roleRepo.purgeExpiredGrants(now).join();
            } catch (RuntimeException e) {
                // Сбой чистки не должен останавливать планировщик: при выброшенном
                // исключении scheduleWithFixedDelay больше не запустится никогда.
                LOGGER.error("Ошибка фонового обслуживания", e);
            }
        }, interval.toMinutes(), interval.toMinutes(), TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------------ KoFAuth

    @Override
    public @NotNull AuthenticationService authentication() {
        return authentication;
    }

    @Override
    public @NotNull RegistrationService registration() {
        return registration;
    }

    @Override
    public @NotNull SessionService sessions() {
        return sessions;
    }

    @Override
    public @NotNull SecurityService security() {
        return security;
    }

    @Override
    public @NotNull CaptchaService captcha() {
        return captcha;
    }

    @Override
    public @NotNull EmailService email() {
        return email;
    }

    @Override
    public @NotNull TotpService totp() {
        return totp;
    }

    @Override
    public @NotNull LinkService links() {
        return links;
    }

    @Override
    public @NotNull TokenService tokens() {
        return tokens;
    }

    @Override
    public @NotNull AuditService audit() {
        return audit;
    }

    @Override
    public @NotNull EventBus events() {
        return eventBus;
    }

    @Override
    public @NotNull ConfigurationService config() {
        return config;
    }

    @Override
    public @NotNull Executor ioExecutor() {
        return executors.io();
    }

    @Override
    public @NotNull String version() {
        return VERSION;
    }

    @Override
    public boolean isReady() {
        return ready && database.isHealthy();
    }

    /** Пулы — платформенным модулям, которым нужен планировщик. */
    public @NotNull AsyncExecutors executors() {
        return executors;
    }

    /** Менеджер базы — для {@code /auth info} и проверок работоспособности. */
    public @NotNull DatabaseManager database() {
        return database;
    }

    /** Кэш — платформенным модулям для собственных ключей. */
    public @NotNull CacheProvider cache() {
        return cache;
    }

    /**
     * Операции для административных команд и панели.
     *
     * @see AdminOperations
     */
    public @NotNull AdminOperations adminOperations() {
        return adminOperations;
    }

    // ------------------------------------------------------------------ жизненный цикл

    /**
     * Запускает KoFAuth и регистрирует его в {@link KoFAuthProvider}.
     *
     * @param configDirectory каталог с YAML-файлами
     * @throws ConfigurationException если конфигурация некорректна или база недоступна
     */
    public static @NotNull KoFAuthCore start(@NotNull Path configDirectory) {
        KoFAuthCore core = new KoFAuthCore(configDirectory);
        KoFAuthProvider.register(core);
        return core;
    }

    /**
     * Останавливает систему.
     *
     * <p>Порядок обратен запуску: сначала перестаём принимать события, затем
     * сбрасываем буфер аудита (иначе события последних секунд потеряются),
     * и только потом закрываем пулы и соединения.
     */
    @Override
    public void close() {
        ready = false;
        LOGGER.info("Остановка KoFAuth...");

        try {
            emailNotifications.close();
            eventBus.close();
        } catch (RuntimeException e) {
            LOGGER.warn("Ошибка при остановке шины событий", e);
        }

        try {
            audit.flush().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("Не удалось сбросить буфер аудита при остановке", e);
        }

        try {
            cache.close();
        } catch (RuntimeException e) {
            LOGGER.warn("Ошибка при закрытии кэша", e);
        }

        executors.shutdown(Duration.ofSeconds(15));
        database.close();
        KoFAuthProvider.unregister();

        LOGGER.info("KoFAuth остановлен");
    }
}
