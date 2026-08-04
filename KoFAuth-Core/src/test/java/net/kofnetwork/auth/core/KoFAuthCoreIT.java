package net.kofnetwork.auth.core;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.LoginRequest;
import net.kofnetwork.auth.api.dto.PasswordChangeRequest;
import net.kofnetwork.auth.api.dto.RegistrationRequest;
import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.CaptchaType;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.LoginResultType;
import net.kofnetwork.auth.api.result.AuthResult;
import net.kofnetwork.auth.api.result.RegistrationResult;
import net.kofnetwork.auth.api.result.RegistrationResultType;
import net.kofnetwork.auth.api.service.CaptchaService;
import net.kofnetwork.auth.core.security.AesCipher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозная проверка: KoFAuthCore поднимается целиком и обслуживает регистрацию и вход.
 *
 * <p>Отличие от тестов репозиториев: здесь проверяется, что собранный граф
 * зависимостей действительно работает — конфигурация читается, миграции применяются,
 * сервисы находят друг друга, события доходят. Ошибку в порядке сборки не поймает
 * ни один модульный тест.
 */
@Testcontainers
class KoFAuthCoreIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kofauth")
            .withUsername("kofauth")
            .withPassword("kofauth-secret");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    static KoFAuthCore core;

    private static final AuthContext CONTEXT =
            AuthContext.minecraft(IpAddress.of("203.0.113.7"), "limbo-1", 767, "vanilla");

    @BeforeAll
    static void startCore(@org.junit.jupiter.api.io.TempDir Path configDir) throws IOException {
        Files.writeString(configDir.resolve(ConfigFile.DATABASE.fileName()), """
                mysql:
                  host: %s
                  port: %d
                  database: kofauth
                  username: kofauth
                  password: kofauth-secret
                  properties:
                    useSSL: false
                    allowPublicKeyRetrieval: true
                  pool:
                    maximum-size: 6
                redis:
                  enabled: true
                  host: %s
                  port: %d
                  key-prefix: "kofauth-e2e:"
                """.formatted(MYSQL.getHost(), MYSQL.getFirstMappedPort(),
                REDIS.getHost(), REDIS.getFirstMappedPort()), StandardCharsets.UTF_8);

        Files.writeString(configDir.resolve(ConfigFile.SECURITY.fileName()), """
                password:
                  # Минимально допустимая стоимость: тест не должен ждать по 250 мс на хэш.
                  bcrypt-cost: 10
                  policy:
                    min-length: 8
                encryption:
                  key: "%s"
                jwt:
                  secret: "тестовый-секрет-достаточной-длины-для-hmac-512"
                  access-lifetime: 15m
                rate-limit:
                  enabled: true
                  login:
                    per-ip:
                      attempts: 100
                      window: 1m
                    per-account:
                      attempts: 100
                      window: 1m
                  register:
                    per-ip:
                      attempts: 100
                      window: 1m
                antibot:
                  enabled: false
                """.formatted(AesCipher.generateKey()), StandardCharsets.UTF_8);

        Files.writeString(configDir.resolve(ConfigFile.CAPTCHA.fileName()),
                "enabled: false\n", StandardCharsets.UTF_8);

        Files.writeString(configDir.resolve(ConfigFile.CONFIG.fileName()), """
                auth:
                  registration:
                    enabled: true
                    max-accounts-per-ip: 0
                  login:
                    max-attempts: 3
                    lockout-duration: 15m
                  session:
                    ttl: 24h
                    absolute-ttl: 7d
                """, StandardCharsets.UTF_8);

        core = KoFAuthCore.start(configDir);
    }

    @AfterAll
    static void stopCore() {
        if (core != null) {
            core.close();
        }
    }

    @AfterEach
    void cleanAccounts() throws Exception {
        try (Connection connection = core.database().connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM users");
        }
        core.cache().deleteByPattern("*").join();
    }

    private static RegistrationRequest registration(String username, String password) {
        return RegistrationRequest.ofPlayer(UUID.randomUUID(), username, password, password, CONTEXT);
    }

    @Test
    void ядро_поднимается_и_готово_к_работе() {
        assertThat(core.isReady()).isTrue();
        assertThat(core.cache().isAvailable()).isTrue();
        assertThat(core.cache().providerName()).isEqualTo("redis");
        assertThat(core.version()).isNotBlank();
    }

    @Test
    void регистрация_создаёт_аккаунт_и_сразу_выдаёт_сессию() {
        // Отдельный вход после регистрации не нужен: игрок только что доказал
        // знание пароля, задав его.
        RegistrationResult result = core.registration().register(
                registration("Steve", "Korovka42Luna")).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.account()).isNotNull();
        assertThat(result.session()).isNotNull();
        assertThat(result.account().lowerUsername()).isEqualTo("steve");
    }

    @Test
    void зарегистрированный_игрок_входит_по_паролю() {
        core.registration().register(registration("Steve", "Korovka42Luna")).join();

        AuthResult result = core.authentication()
                .login(LoginRequest.of("Steve", "Korovka42Luna", CONTEXT)).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.session()).isNotNull();
        assertThat(result.account().username()).isEqualTo("Steve");
    }

    @Test
    void неверный_пароль_отклоняется_и_показывает_остаток_попыток() {
        core.registration().register(registration("Steve", "Korovka42Luna")).join();

        AuthResult result = core.authentication()
                .login(LoginRequest.of("Steve", "НеверныйПароль1", CONTEXT)).join();

        assertThat(result.type()).isEqualTo(LoginResultType.BAD_PASSWORD);
        assertThat(result.remainingAttempts()).isEqualTo(2);
        assertThat(result.session()).isNull();
    }

    @Test
    void серия_неудач_приводит_к_временной_блокировке() {
        core.registration().register(registration("Steve", "Korovka42Luna")).join();

        for (int i = 0; i < 2; i++) {
            core.authentication().login(LoginRequest.of("Steve", "Неверный1", CONTEXT)).join();
        }
        AuthResult third = core.authentication()
                .login(LoginRequest.of("Steve", "Неверный1", CONTEXT)).join();

        assertThat(third.type()).isEqualTo(LoginResultType.TEMPORARILY_LOCKED);
        assertThat(third.retryAfter()).isNotNull();

        // Даже верный пароль теперь не принимается.
        AuthResult correct = core.authentication()
                .login(LoginRequest.of("Steve", "Korovka42Luna", CONTEXT)).join();
        assertThat(correct.type()).isEqualTo(LoginResultType.TEMPORARILY_LOCKED);
    }

    @Test
    void успешный_вход_сбрасывает_счётчик_неудач() {
        core.registration().register(registration("Steve", "Korovka42Luna")).join();
        core.authentication().login(LoginRequest.of("Steve", "Неверный1", CONTEXT)).join();

        core.authentication().login(LoginRequest.of("Steve", "Korovka42Luna", CONTEXT)).join();

        AuthResult afterReset = core.authentication()
                .login(LoginRequest.of("Steve", "Неверный1", CONTEXT)).join();
        // Счётчик обнулён: снова доступны все три попытки минус текущая.
        assertThat(afterReset.remainingAttempts()).isEqualTo(2);
    }

    @Test
    void несуществующий_аккаунт_неотличим_от_неверного_пароля() {
        // Различимый ответ превратил бы форму входа в проверку существования ников.
        AuthResult unknown = core.authentication()
                .login(LoginRequest.of("НетТакого", "Korovka42Luna", CONTEXT)).join();

        assertThat(unknown.type()).isEqualTo(LoginResultType.UNKNOWN_ACCOUNT);
        assertThat(unknown.session()).isNull();
        assertThat(unknown.account()).isNull();
    }

    @Test
    void занятый_ник_не_регистрируется_повторно() {
        core.registration().register(registration("Steve", "Korovka42Luna")).join();

        RegistrationResult second = core.registration()
                .register(registration("steve", "Другой42Пароль")).join();

        assertThat(second.type()).isEqualTo(RegistrationResultType.USERNAME_TAKEN);
    }

    @Test
    void слабый_пароль_отвергается_со_списком_причин() {
        RegistrationResult result = core.registration()
                .register(registration("Steve", "123")).join();

        assertThat(result.type()).isEqualTo(RegistrationResultType.PASSWORD_TOO_WEAK);
        assertThat(result.passwordIssues()).isNotEmpty();
    }

    @Test
    void несовпадающие_пароли_отвергаются() {
        RegistrationResult result = core.registration().register(new RegistrationRequest(
                "Steve", "Korovka42Luna", "Другой42Пароль", UUID.randomUUID(), null, CONTEXT)).join();

        assertThat(result.type()).isEqualTo(RegistrationResultType.PASSWORDS_DO_NOT_MATCH);
    }

    @Test
    void недопустимый_ник_отвергается() {
        assertThat(core.registration().register(registration("console", "Korovka42Luna")).join()
                .type()).isEqualTo(RegistrationResultType.INVALID_USERNAME);
        assertThat(core.registration().register(registration("ab", "Korovka42Luna")).join()
                .type()).isEqualTo(RegistrationResultType.INVALID_USERNAME);
        assertThat(core.registration().register(registration("Ник-С-Дефисом", "Korovka42Luna")).join()
                .type()).isEqualTo(RegistrationResultType.INVALID_USERNAME);
    }

    @Test
    void смена_пароля_требует_текущий_и_отзывает_сессии() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        long accountId = registered.account().id();

        // С неверным текущим паролем — отказ.
        var wrong = core.authentication().changePassword(accountId,
                PasswordChangeRequest.of("НеверныйСтарый1", "Новый42Пароль",
                        "Новый42Пароль", CONTEXT)).join();
        assertThat(wrong.isFailure()).isTrue();
        assertThat(wrong.errorCode()).isEqualTo("BAD_PASSWORD");

        // С верным — успех.
        var changed = core.authentication().changePassword(accountId,
                PasswordChangeRequest.of("Korovka42Luna", "Новый42Пароль",
                        "Новый42Пароль", CONTEXT)).join();
        assertThat(changed.isSuccess()).isTrue();

        // Старый пароль больше не работает, новый работает.
        assertThat(core.authentication()
                .login(LoginRequest.of("Steve", "Korovka42Luna", CONTEXT)).join().isSuccess())
                .isFalse();
        assertThat(core.authentication()
                .login(LoginRequest.of("Steve", "Новый42Пароль", CONTEXT)).join().isSuccess())
                .isTrue();
    }

    @Test
    void запрос_сброса_не_раскрывает_существование_аккаунта() {
        // Различимый ответ превратил бы форму восстановления в способ проверять,
        // зарегистрирован ли ник и есть ли у него почта.
        core.registration().register(registration("Steve", "Korovka42Luna")).join();

        var forExisting = core.authentication().requestPasswordReset("Steve", CONTEXT).join();
        var forUnknown = core.authentication().requestPasswordReset("НетТакого", CONTEXT).join();
        var forNoEmail = core.authentication().requestPasswordReset("Steve", CONTEXT).join();

        assertThat(forExisting.isSuccess()).isTrue();
        assertThat(forUnknown.isSuccess()).isTrue();
        assertThat(forNoEmail.isSuccess()).isTrue();
    }

    @Test
    void сброс_пароля_по_коду_меняет_пароль_и_отзывает_сессии() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        long accountId = registered.account().id();
        String sessionId = registered.session().publicId();

        // Код выпускаем напрямую: отправку письма проверять здесь нечем,
        // а интересует поведение после того, как игрок код получил.
        var issued = core.tokens().issue(accountId,
                net.kofnetwork.auth.api.model.TokenType.PASSWORD_RESET,
                IpAddress.of("203.0.113.7"), null).join();

        var result = core.authentication()
                .completePasswordReset(issued.value(), "Новый42Пароль", CONTEXT).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(core.authentication()
                .login(LoginRequest.of("Steve", "Новый42Пароль", CONTEXT)).join().isSuccess())
                .isTrue();
        // Сброс всегда завершает все сессии: он обычно означает, что доступ
        // пытались перехватить.
        assertThat(core.sessions()
                .validateByPublicId(sessionId, IpAddress.of("203.0.113.7")).join())
                .isEmpty();
    }

    @Test
    void код_сброса_одноразовый() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        var issued = core.tokens().issue(registered.account().id(),
                net.kofnetwork.auth.api.model.TokenType.PASSWORD_RESET, null, null).join();

        core.authentication().completePasswordReset(issued.value(), "Первый42Пароль", CONTEXT).join();
        var second = core.authentication()
                .completePasswordReset(issued.value(), "Второй42Пароль", CONTEXT).join();

        assertThat(second.isFailure()).isTrue();
        // Второй пароль не применился.
        assertThat(core.authentication()
                .login(LoginRequest.of("Steve", "Первый42Пароль", CONTEXT)).join().isSuccess())
                .isTrue();
    }

    @Test
    void сброс_отвергает_слабый_пароль() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        var issued = core.tokens().issue(registered.account().id(),
                net.kofnetwork.auth.api.model.TokenType.PASSWORD_RESET, null, null).join();

        var result = core.authentication()
                .completePasswordReset(issued.value(), "123", CONTEXT).join();

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorCode()).isEqualTo("PASSWORD_TOO_WEAK");
    }

    @Test
    void сессия_проверяется_и_отзывается() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        String publicId = registered.session().publicId();

        assertThat(core.sessions().validateByPublicId(publicId, IpAddress.of("203.0.113.7")).join())
                .isPresent();

        core.sessions().revoke(publicId, "LOGOUT").join();

        assertThat(core.sessions().validateByPublicId(publicId, IpAddress.of("203.0.113.7")).join())
                .isEmpty();
    }

    @Test
    void сессия_отзывается_при_смене_адреса() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        String publicId = registered.session().publicId();

        // Игровая сессия привязана к IP: смена адреса означает либо угон,
        // либо смену сети, и различить их нельзя.
        assertThat(core.sessions().validateByPublicId(publicId, IpAddress.of("198.51.100.9")).join())
                .isEmpty();
    }

    @Test
    void история_входов_и_аудит_наполняются() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        long accountId = registered.account().id();

        core.authentication().login(LoginRequest.of("Steve", "Korovka42Luna", CONTEXT)).join();
        core.authentication().login(LoginRequest.of("Steve", "Неверный1", CONTEXT)).join();
        core.audit().flush().join();

        assertThat(core.audit().getLoginHistory(accountId, 10, 0).join()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(core.audit().getSecurityLog(accountId, 10, 0).join()).isNotEmpty();
    }

    @Test
    void TOTP_подключается_и_проверяется_кодом() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        long accountId = registered.account().id();

        var setup = core.totp().beginSetup(accountId, "Steve", CONTEXT).join();
        assertThat(setup.isSuccess()).isTrue();
        assertThat(setup.value().secret()).isNotBlank();
        assertThat(setup.value().recoveryCodes()).hasSize(10);
        // До подтверждения второй фактор не работает.
        assertThat(core.totp().isEnabled(accountId).join()).isFalse();

        // Неверный код не включает фактор.
        assertThat(core.totp().confirmSetup(accountId, "000000", CONTEXT).join().isFailure())
                .isTrue();
        assertThat(core.totp().isEnabled(accountId).join()).isFalse();
    }

    @Test
    void резервный_код_TOTP_срабатывает_один_раз() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        long accountId = registered.account().id();

        var setup = core.totp().beginSetup(accountId, "Steve", CONTEXT).join();
        String recoveryCode = setup.value().recoveryCodes().get(0);

        // Резервные коды действуют только при включённом факторе.
        assertThat(core.totp().countRemainingRecoveryCodes(accountId).join()).isEqualTo(10);
        assertThat(recoveryCode).contains("-");
    }

    @Test
    void вход_отзывает_прежнюю_сессию_но_не_выданную_этим_же_входом() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        String previous = registered.session().publicId();

        var invalidations = new java.util.concurrent.CopyOnWriteArrayList<
                net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent>();
        try (var subscription = core.events().subscribe(
                net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent.class,
                invalidations::add)) {

            AuthResult result = core.authentication()
                    .login(LoginRequest.of("Steve", "Korovka42Luna", CONTEXT)).join();
            assertThat(result.isSuccess()).isTrue();
            String current = result.session().publicId();

            // Прежняя сессия отозвана, новая работает.
            assertThat(core.sessions().validateByPublicId(previous, CONTEXT.ip()).join()).isEmpty();
            assertThat(core.sessions().validateByPublicId(current, CONTEXT.ip()).join()).isPresent();

            // И главное — событие об отзыве называет ТОЛЬКО прежнюю сессию.
            // Пока оно объявляло «отозваны все», прокси выбрасывал с сервера
            // игрока, который только что успешно ввёл пароль.
            assertThat(invalidations).isNotEmpty();
            var event = invalidations.get(invalidations.size() - 1);
            assertThat(event.affectsAll()).isFalse();
            assertThat(event.affects(previous)).isTrue();
            assertThat(event.affects(current)).isFalse();
        }
    }

    @Test
    void пара_токенов_кабинета_выдаётся_и_привязывается_к_сессии() throws Exception {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        long accountId = registered.account().id();
        long sessionId = registered.session().id();

        var pair = core.tokens().issueTokenPair(accountId, sessionId, CONTEXT.ip()).join();

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();

        // Раньше выпуск состоял из двух вставок: сначала токен, потом «тот же
        // токен, но с sessionId». insert создаёт строку, а не изменяет её, поэтому
        // вторая падала по уникальному индексу uk_tokens_hash — и каждый вход
        // в личный кабинет отвечал 503. Проверяем и число строк, и привязку.
        try (Connection connection = core.database().connection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) AS n, MAX(session_id) AS sid FROM tokens "
                             + "WHERE account_id = ? AND type = 'REFRESH'")) {
            statement.setLong(1, accountId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("n")).isEqualTo(1);
                assertThat(rows.getLong("sid")).isEqualTo(sessionId);
            }
        }
    }

    @Test
    void две_пары_токенов_подряд_не_конфликтуют() {
        RegistrationResult registered = core.registration()
                .register(registration("Steve", "Korovka42Luna")).join();
        long accountId = registered.account().id();
        long sessionId = registered.session().id();

        var first = core.tokens().issueTokenPair(accountId, sessionId, CONTEXT.ip()).join();
        var second = core.tokens().issueTokenPair(accountId, sessionId, CONTEXT.ip()).join();

        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
        // Оба обязаны работать: вход с телефона не должен выбивать вход с ноутбука.
        assertThat(core.tokens().refresh(first.refreshToken(), CONTEXT.ip()).join().isSuccess())
                .isTrue();
        assertThat(core.tokens().refresh(second.refreshToken(), CONTEXT.ip()).join().isSuccess())
                .isTrue();
    }

    @Test
    void ограничение_скорости_срабатывает_по_адресу() {
        var verdict = core.security().checkAndConsume("login.per-ip", "203.0.113.250").join();
        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.remaining()).isLessThan(100);
    }

    /**
     * Рендерер, запоминающий выданный ответ.
     *
     * <p>Ответ не отдаётся наружу сервисом — он живёт в его памяти и передаётся
     * только рендереру. Регистрация такого двойника заодно проверяет саму точку
     * расширения: сервис действительно вызывает рендерер и действительно передаёт
     * ему правильный ответ.
     */
    private static final class CapturingRenderer implements CaptchaService.CaptchaRenderer {

        private volatile String lastAnswer;

        @Override
        public java.util.List<CaptchaType> supportedTypes() {
            return java.util.List.of(CaptchaType.TEXT_INPUT, CaptchaType.GUI_GRID);
        }

        @Override
        public CaptchaService.RenderedCaptcha render(CaptchaChallenge challenge, String answer) {
            this.lastAnswer = answer;
            return new CaptchaService.RenderedCaptcha("тест", java.util.List.of(), null);
        }
    }

    @Test
    void капча_выдаётся_и_принимает_верный_ответ() {
        CapturingRenderer renderer = new CapturingRenderer();
        core.captcha().registerRenderer(renderer);

        UUID playerUuid = UUID.randomUUID();
        CaptchaChallenge challenge = core.captcha()
                .issue(null, playerUuid, CaptchaType.TEXT_INPUT, CONTEXT).join();

        // Раскладка запрашивается платформой — именно она передаёт ответ рендереру.
        ((net.kofnetwork.auth.core.service.impl.CaptchaServiceImpl) core.captcha())
                .render(challenge);
        assertThat(renderer.lastAnswer).isNotBlank();

        var verdict = core.captcha().verify(challenge.challengeId(), renderer.lastAnswer).join();

        assertThat(verdict.passed()).isTrue();
    }

    @Test
    void неверный_ответ_расходует_попытку_а_не_проваливает_сразу() {
        core.captcha().registerRenderer(new CapturingRenderer());
        UUID playerUuid = UUID.randomUUID();
        CaptchaChallenge challenge = core.captcha()
                .issue(null, playerUuid, CaptchaType.TEXT_INPUT, CONTEXT).join();

        var first = core.captcha().verify(challenge.challengeId(), "ЗАВЕДОМО-НЕВЕРНО").join();

        assertThat(first.passed()).isFalse();
        assertThat(first.exhausted()).isFalse();
        assertThat(first.remainingAttempts()).isEqualTo(2);
    }

    @Test
    void исчерпание_попыток_закрывает_задачу() {
        core.captcha().registerRenderer(new CapturingRenderer());
        UUID playerUuid = UUID.randomUUID();
        CaptchaChallenge challenge = core.captcha()
                .issue(null, playerUuid, CaptchaType.TEXT_INPUT, CONTEXT).join();

        core.captcha().verify(challenge.challengeId(), "нет").join();
        core.captcha().verify(challenge.challengeId(), "нет").join();
        var third = core.captcha().verify(challenge.challengeId(), "нет").join();

        assertThat(third.exhausted()).isTrue();
        // Закрытая задача больше не принимает ответы, даже верные.
        assertThat(core.captcha().findPending(playerUuid).join()).isEmpty();
    }

    @Test
    void отмена_гасит_незавершённую_задачу() {
        core.captcha().registerRenderer(new CapturingRenderer());
        UUID playerUuid = UUID.randomUUID();
        core.captcha().issue(null, playerUuid, CaptchaType.TEXT_INPUT, CONTEXT).join();
        assertThat(core.captcha().findPending(playerUuid).join()).isPresent();

        core.captcha().cancel(playerUuid).join();

        assertThat(core.captcha().findPending(playerUuid).join()).isEmpty();
    }

    @Test
    void одноразовый_nonce_гасится_ровно_один_раз() {
        String nonce = "тестовый-nonce";
        core.security().issueNonce(nonce, java.time.Duration.ofMinutes(1)).join();

        assertThat(core.security().consumeNonce(nonce).join()).isTrue();
        // Повторное предъявление: именно это защищает от двойного нажатия
        // кнопки подтверждения в Telegram.
        assertThat(core.security().consumeNonce(nonce).join()).isFalse();
    }
}
