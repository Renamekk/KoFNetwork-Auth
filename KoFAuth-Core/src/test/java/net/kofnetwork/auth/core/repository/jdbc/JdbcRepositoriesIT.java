package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.AccountStatus;
import net.kofnetwork.auth.api.model.AuthToken;
import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.CaptchaType;
import net.kofnetwork.auth.api.model.DevicePlatform;
import net.kofnetwork.auth.api.model.EmailBinding;
import net.kofnetwork.auth.api.model.EventSource;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.LoginAttempt;
import net.kofnetwork.auth.api.model.LoginResultType;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.SecurityLogEntry;
import net.kofnetwork.auth.api.model.ServerNode;
import net.kofnetwork.auth.api.model.ServerType;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.api.model.Severity;
import net.kofnetwork.auth.api.model.TelegramBinding;
import net.kofnetwork.auth.api.model.TokenType;
import net.kofnetwork.auth.api.model.TotpSecret;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import net.kofnetwork.auth.api.repository.TokenRepository;
import net.kofnetwork.auth.core.concurrent.AsyncExecutors;
import net.kofnetwork.auth.core.config.YamlConfigurationService;
import net.kofnetwork.auth.core.database.DatabaseManager;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.security.AesCipher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверка всех JDBC-репозиториев на настоящем MySQL.
 *
 * <p>Здесь проверяется ровно то, ради чего репозитории существуют: преобразование
 * типов туда и обратно, атомарность операций, работа индексов и каскадов. Ни одно
 * из этих свойств не проверяется моками — мок JDBC подтвердил бы только, что мы
 * умеем вызывать моки.
 */
@Testcontainers
class JdbcRepositoriesIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kofauth")
            .withUsername("kofauth")
            .withPassword("kofauth-secret");

    static AsyncExecutors executors;
    static DatabaseManager database;
    static SqlExecutor sql;
    static AesCipher cipher;

    static JdbcAccountRepository accounts;
    static JdbcSessionRepository sessions;
    static JdbcDeviceRepository devices;
    static JdbcTokenRepository tokens;
    static JdbcLoginHistoryRepository history;
    static JdbcSecurityLogRepository audit;
    static JdbcEmailRepository emails;
    static JdbcTelegramRepository telegram;
    static JdbcTotpRepository totp;
    static JdbcCaptchaRepository captcha;
    static JdbcRoleRepository roles;
    static JdbcSettingsRepository settings;
    static JdbcServerRepository servers;
    static JdbcBotOutboxRepository outbox;
    static JdbcLoginApprovalRepository approvals;

    @BeforeAll
    static void startAll(@org.junit.jupiter.api.io.TempDir Path configDir) throws IOException {
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
                """.formatted(MYSQL.getHost(), MYSQL.getFirstMappedPort()), StandardCharsets.UTF_8);

        YamlConfigurationService config = new YamlConfigurationService(configDir, Runnable::run);
        config.initialize();

        executors = new AsyncExecutors(6);
        database = new DatabaseManager(config, executors.io());
        database.start();
        sql = new SqlExecutor(database, executors);
        cipher = AesCipher.fromBase64(AesCipher.generateKey());

        accounts = new JdbcAccountRepository(sql);
        sessions = new JdbcSessionRepository(sql);
        devices = new JdbcDeviceRepository(sql);
        tokens = new JdbcTokenRepository(sql);
        history = new JdbcLoginHistoryRepository(sql);
        audit = new JdbcSecurityLogRepository(sql);
        emails = new JdbcEmailRepository(sql);
        telegram = new JdbcTelegramRepository(sql);
        totp = new JdbcTotpRepository(sql, cipher);
        captcha = new JdbcCaptchaRepository(sql);
        roles = new JdbcRoleRepository(sql);
        settings = new JdbcSettingsRepository(sql);
        servers = new JdbcServerRepository(sql);
        outbox = new JdbcBotOutboxRepository(sql);
        approvals = new JdbcLoginApprovalRepository(sql);
    }

    @AfterAll
    static void stopAll() {
        if (database != null) {
            database.close();
        }
        if (executors != null) {
            executors.close();
        }
    }

    /** Каждый тест начинает с чистых пользовательских данных, справочники остаются. */
    @BeforeEach
    void cleanUp() throws Exception {
        try (Connection connection = database.connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM users");
            statement.executeUpdate("DELETE FROM servers");
            statement.executeUpdate("DELETE FROM captcha");
            // Очередь ботов на аккаунт не ссылается, поэтому каскадом не чистится.
            statement.executeUpdate("DELETE FROM bot_outbox");
        }
    }

    private static Account newAccount(String username) {
        return Account.newAccount(UUID.randomUUID(), username,
                "$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTU",
                IpAddress.of("203.0.113.7")).build();
    }

    private static long persist(String username) {
        return accounts.insert(newAccount(username)).join().id();
    }

    // ================================================================ аккаунты

    @Test
    void аккаунт_сохраняется_и_читается_без_потерь() {
        Account original = newAccount("Steve").toBuilder()
                .lastCountry("RU")
                .lastCity("Москва")
                .twoFactorMethods(Set.of(TwoFactorMethod.TOTP, TwoFactorMethod.TELEGRAM))
                .build();

        Account saved = accounts.insert(original).join();
        Account loaded = accounts.findById(saved.id()).join().orElseThrow();

        assertThat(loaded.uuid()).isEqualTo(original.uuid());
        assertThat(loaded.username()).isEqualTo("Steve");
        assertThat(loaded.lowerUsername()).isEqualTo("steve");
        assertThat(loaded.registrationIp()).isEqualTo(IpAddress.of("203.0.113.7"));
        assertThat(loaded.twoFactorMethods())
                .containsExactlyInAnyOrder(TwoFactorMethod.TOTP, TwoFactorMethod.TELEGRAM);
    }

    @Test
    void поиск_по_нику_не_чувствителен_к_регистру() {
        persist("Steve");

        assertThat(accounts.findByUsername("STEVE").join()).isPresent();
        assertThat(accounts.findByUsername("steve").join()).isPresent();
        assertThat(accounts.existsByUsername("StEvE").join()).isTrue();
        assertThat(accounts.existsByUsername("Alex").join()).isFalse();
    }

    @Test
    void поиск_по_UUID_работает_через_BINARY16() {
        Account saved = accounts.insert(newAccount("Steve")).join();

        assertThat(accounts.findByUuid(saved.uuid()).join()).isPresent();
        assertThat(accounts.findByUuid(UUID.randomUUID()).join()).isEmpty();
    }

    @Test
    void повторная_регистрация_ника_даёт_DuplicateKeyException() {
        // Именно этот тип ловит RegistrationService, чтобы вернуть USERNAME_TAKEN,
        // а не «внутренняя ошибка».
        persist("Steve");

        assertThatThrownBy(() -> accounts.insert(newAccount("steve")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(SqlExecutor.DuplicateKeyException.class);
    }

    @Test
    void счётчик_неудач_растёт_атомарно_при_параллельных_вызовах() {
        // Read-modify-write здесь терял бы инкременты, и лимит попыток
        // перестал бы срабатывать под параллельным перебором.
        long id = persist("Steve");

        List<Integer> results = IntStream.range(0, 50).parallel()
                .mapToObj(i -> accounts.incrementFailedAttempts(id).join())
                .toList();

        assertThat(accounts.findById(id).join().orElseThrow().failedLoginAttempts()).isEqualTo(50);
        assertThat(results).hasSize(50);
    }

    @Test
    void успешный_вход_сбрасывает_счётчик_и_блокировку() {
        long id = persist("Steve");
        accounts.incrementFailedAttempts(id).join();
        accounts.lockUntil(id, Instant.now().plusSeconds(600)).join();

        Instant now = Instant.now();
        accounts.updateLastLogin(id, IpAddress.of("198.51.100.5"), now,
                "lobby-1", "DE", "Берлин", "vanilla").join();

        Account loaded = accounts.findById(id).join().orElseThrow();
        assertThat(loaded.failedLoginAttempts()).isZero();
        assertThat(loaded.lockedUntil()).isNull();
        assertThat(loaded.lastLoginIp()).isEqualTo(IpAddress.of("198.51.100.5"));
        assertThat(loaded.lastCountry()).isEqualTo("DE");
    }

    @Test
    void время_сохраняется_с_точностью_до_миллисекунд_в_UTC() {
        // DATETIME(3) и connectionTimeZone=UTC: момент не должен «уехать».
        Instant precise = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        long id = persist("Steve");

        accounts.updateLastLogin(id, IpAddress.of("203.0.113.7"), precise,
                null, null, null, null).join();

        assertThat(accounts.findById(id).join().orElseThrow().lastLoginAt()).isEqualTo(precise);
    }

    @Test
    void поиск_по_префиксу_экранирует_шаблон_LIKE() {
        persist("Steve");
        persist("Steven");
        persist("Alex");

        assertThat(accounts.searchByUsernamePrefix("Ste", 10).join()).hasSize(2);
        // '%' — не шаблон, а обычный символ: иначе автодополнение выбрало бы всю таблицу.
        assertThat(accounts.searchByUsernamePrefix("%", 10).join()).isEmpty();
    }

    @Test
    void считает_регистрации_с_адреса() {
        persist("Steve");
        persist("Alex");

        Instant since = Instant.now().minus(Duration.ofDays(1));
        assertThat(accounts.countRegistrationsFromIp(IpAddress.of("203.0.113.7"), since).join())
                .isEqualTo(2);
        assertThat(accounts.countRegistrationsFromIp(IpAddress.of("198.51.100.1"), since).join())
                .isZero();
    }

    // ================================================================ сессии

    @Test
    void сессия_сохраняется_и_находится_по_публичному_идентификатору() {
        long accountId = persist("Steve");
        Session created = sessions.insert(Session.create(accountId, null, SessionType.GAME,
                IpAddress.of("203.0.113.7"), "vanilla",
                Duration.ofHours(1), Duration.ofDays(7))).join();

        Session loaded = sessions.findByPublicId(created.publicId()).join().orElseThrow();

        assertThat(loaded.accountId()).isEqualTo(accountId);
        assertThat(loaded.ip()).isEqualTo(IpAddress.of("203.0.113.7"));
        assertThat(loaded.isValid(Instant.now())).isTrue();
    }

    @Test
    void продление_не_может_превысить_жёсткий_потолок() {
        // LEAST в SQL: иначе угнанную сессию можно было бы «прогревать» бесконечно.
        long accountId = persist("Steve");
        Session created = sessions.insert(Session.create(accountId, null, SessionType.GAME,
                IpAddress.of("203.0.113.7"), null,
                Duration.ofMinutes(30), Duration.ofHours(1))).join();

        Instant farFuture = Instant.now().plus(Duration.ofDays(30));
        sessions.touch(created.id(), Instant.now(), farFuture).join();

        Session loaded = sessions.findById(created.id()).join().orElseThrow();
        assertThat(loaded.expiresAt()).isBeforeOrEqualTo(created.absoluteExpiresAt());
    }

    @Test
    void отзыв_идемпотентен_и_сохраняет_исходную_причину() {
        long accountId = persist("Steve");
        Session created = sessions.insert(Session.create(accountId, null, SessionType.GAME,
                IpAddress.of("203.0.113.7"), null,
                Duration.ofHours(1), Duration.ofDays(7))).join();

        assertThat(sessions.revoke(created.publicId(), Instant.now(), Session.REASON_LOGOUT).join())
                .isTrue();
        assertThat(sessions.revoke(created.publicId(), Instant.now(), Session.REASON_ADMIN).join())
                .isFalse();

        Session loaded = sessions.findByPublicId(created.publicId()).join().orElseThrow();
        assertThat(loaded.revokedReason()).isEqualTo(Session.REASON_LOGOUT);
    }

    @Test
    void выход_со_всех_устройств_кроме_текущего() {
        long accountId = persist("Steve");
        Session keep = sessions.insert(Session.create(accountId, null, SessionType.WEB,
                IpAddress.of("203.0.113.7"), null, Duration.ofHours(1), Duration.ofDays(7))).join();
        sessions.insert(Session.create(accountId, null, SessionType.GAME,
                IpAddress.of("198.51.100.1"), null, Duration.ofHours(1), Duration.ofDays(7))).join();
        sessions.insert(Session.create(accountId, null, SessionType.GAME,
                IpAddress.of("198.51.100.2"), null, Duration.ofHours(1), Duration.ofDays(7))).join();

        int revoked = sessions.revokeAllForAccount(accountId, keep.publicId(),
                Instant.now(), Session.REASON_LOGOUT_ALL).join();

        assertThat(revoked).isEqualTo(2);
        assertThat(sessions.findActiveByAccount(accountId, Instant.now()).join())
                .singleElement()
                .satisfies(session -> assertThat(session.publicId()).isEqualTo(keep.publicId()));
    }

    @Test
    void истёкшие_сессии_помечаются_планировщиком() {
        long accountId = persist("Steve");
        Session expired = sessions.insert(Session.create(accountId, null, SessionType.GAME,
                IpAddress.of("203.0.113.7"), null,
                Duration.ofMillis(1), Duration.ofMillis(1))).join();

        int affected = sessions.revokeExpired(Instant.now().plusSeconds(5)).join();

        assertThat(affected).isGreaterThanOrEqualTo(1);
        assertThat(sessions.findByPublicId(expired.publicId()).join().orElseThrow().revoked())
                .isTrue();
    }

    // ================================================================ устройства

    @Test
    void устройство_создаётся_один_раз_и_потом_обновляется() {
        long accountId = persist("Steve");
        String fingerprint = "a".repeat(64);

        var first = devices.findOrCreate(accountId, fingerprint, DevicePlatform.MINECRAFT,
                IpAddress.of("203.0.113.7"), Instant.now()).join();
        var second = devices.findOrCreate(accountId, fingerprint, DevicePlatform.MINECRAFT,
                IpAddress.of("198.51.100.1"), Instant.now().plusSeconds(60)).join();

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.device().id()).isEqualTo(first.device().id());
        assertThat(second.device().lastSeenIp()).isEqualTo(IpAddress.of("198.51.100.1"));
        assertThat(devices.countByAccount(accountId).join()).isEqualTo(1);
    }

    @Test
    void одновременное_создание_устройства_не_падает_на_уникальном_ключе() {
        long accountId = persist("Steve");
        String fingerprint = "b".repeat(64);
        Instant now = Instant.now();

        List<Long> ids = IntStream.range(0, 20).parallel()
                .mapToObj(i -> devices.findOrCreate(accountId, fingerprint,
                        DevicePlatform.MINECRAFT, IpAddress.of("203.0.113.7"), now).join())
                .map(result -> result.device().id())
                .distinct()
                .toList();

        assertThat(ids).hasSize(1);
    }

    @Test
    void блокировка_устройства_снимает_доверие() {
        long accountId = persist("Steve");
        var device = devices.findOrCreate(accountId, "c".repeat(64), DevicePlatform.MINECRAFT,
                IpAddress.of("203.0.113.7"), Instant.now()).join().device();
        devices.setTrusted(device.id(), true, Instant.now()).join();

        devices.setBlocked(device.id(), true, Instant.now()).join();

        var loaded = devices.findById(device.id()).join().orElseThrow();
        assertThat(loaded.blocked()).isTrue();
        assertThat(loaded.trusted()).isFalse();
        assertThat(loaded.skipsTwoFactor()).isFalse();
    }

    // ================================================================ токены

    @Test
    void токен_сохраняется_и_находится_по_хэшу() {
        long accountId = persist("Steve");
        AuthToken token = tokens.insert(
                AuthToken.issue(accountId, TokenType.PASSWORD_RESET, "d".repeat(64),
                        IpAddress.of("203.0.113.7"))).join();

        assertThat(tokens.findByHash("d".repeat(64)).join()).isPresent();
        assertThat(token.id()).isPositive();
    }

    @Test
    void одноразовый_токен_гасится_ровно_один_раз_под_параллельной_нагрузкой() {
        // Ключевое свойство: два запроса с одним кодом сброса пароля не должны
        // оба пройти проверку «не использован».
        long accountId = persist("Steve");
        AuthToken token = tokens.insert(AuthToken.issue(accountId, TokenType.PASSWORD_RESET,
                "e".repeat(64), null)).join();

        List<Boolean> results = IntStream.range(0, 30).parallel()
                .mapToObj(i -> tokens.markUsed(token.id(), Instant.now(), null).join())
                .toList();

        assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(1);
    }

    @Test
    void погашение_по_хэшу_атомарно_под_параллельной_нагрузкой() {
        // Регрессия на TOCTOU. Раньше сервис читал строку, убеждался, что токен
        // не использован, не отозван и не истёк, и только потом помечал его.
        // Между чтением и пометкой помещался второй запрос с тем же кодом: он
        // видел ту же пригодную строку и тоже шёл дальше. Теперь всё условие
        // живёт в WHERE одного UPDATE.
        long accountId = persist("Steve");
        tokens.insert(AuthToken.issue(accountId, TokenType.PASSWORD_RESET,
                "f".repeat(64), null)).join();

        List<TokenRepository.ConsumeOutcome> results = IntStream.range(0, 30).parallel()
                .mapToObj(i -> tokens.consumeByHash("f".repeat(64), TokenType.PASSWORD_RESET,
                        Instant.now(), null).join())
                .toList();

        assertThat(results.stream().filter(TokenRepository.ConsumeOutcome::isConsumed))
                .as("выиграть обязан ровно один запрос")
                .hasSize(1);
        assertThat(results.stream()
                .filter(outcome -> outcome.status() == TokenRepository.ConsumeStatus.ALREADY_USED))
                .as("остальные обязаны узнать, что токен уже использован")
                .hasSize(29);
    }

    @Test
    void погашение_различает_причины_отказа() {
        long accountId = persist("Steve");

        // Токена нет вовсе.
        assertThat(tokens.consumeByHash("0".repeat(64), TokenType.PASSWORD_RESET,
                        Instant.now(), null).join().status())
                .isEqualTo(TokenRepository.ConsumeStatus.NOT_FOUND);

        // Тип не тот: код привязки Telegram не может сбросить пароль.
        tokens.insert(AuthToken.issue(accountId, TokenType.TELEGRAM_LINK,
                "6".repeat(64), null)).join();
        assertThat(tokens.consumeByHash("6".repeat(64), TokenType.PASSWORD_RESET,
                        Instant.now(), null).join().status())
                .isEqualTo(TokenRepository.ConsumeStatus.WRONG_TYPE);

        // Срок истёк.
        tokens.insert(AuthToken.issue(accountId, TokenType.PASSWORD_RESET, "7".repeat(64), null)
                .withExpiry(Instant.now().minusSeconds(60))).join();
        assertThat(tokens.consumeByHash("7".repeat(64), TokenType.PASSWORD_RESET,
                        Instant.now(), null).join().status())
                .isEqualTo(TokenRepository.ConsumeStatus.EXPIRED);

        // Токен отозван.
        AuthToken revoked = tokens.insert(AuthToken.issue(accountId, TokenType.PASSWORD_RESET,
                "8".repeat(64), null)).join();
        tokens.revoke(revoked.id(), Instant.now()).join();
        assertThat(tokens.consumeByHash("8".repeat(64), TokenType.PASSWORD_RESET,
                        Instant.now(), null).join().status())
                .isEqualTo(TokenRepository.ConsumeStatus.REVOKED);
    }

    @Test
    void отзыв_цепочки_ротации_гасит_всех_предков_и_потомков() {
        // При обнаружении повторного использования refresh цепочка компрометирована
        // целиком, и выяснять, какая из сторон настоящая, поздно.
        long accountId = persist("Steve");
        AuthToken first = tokens.insert(
                AuthToken.issue(accountId, TokenType.REFRESH, "1".repeat(64), null)).join();
        AuthToken second = tokens.insert(
                AuthToken.issue(accountId, TokenType.REFRESH, "2".repeat(64), null)
                        .withParent(first.id())).join();
        AuthToken third = tokens.insert(
                AuthToken.issue(accountId, TokenType.REFRESH, "3".repeat(64), null)
                        .withParent(second.id())).join();

        // Отзыв инициирован со среднего звена.
        int revoked = tokens.revokeChain(second.id(), Instant.now()).join();

        assertThat(revoked).isEqualTo(3);
        assertThat(tokens.findById(first.id()).join().orElseThrow().revoked()).isTrue();
        assertThat(tokens.findById(third.id()).join().orElseThrow().revoked()).isTrue();
    }

    @Test
    void считает_действующие_резервные_коды() {
        long accountId = persist("Steve");
        for (int i = 0; i < 5; i++) {
            tokens.insert(AuthToken.issue(accountId, TokenType.TOTP_RECOVERY,
                    String.valueOf((char) ('a' + i)).repeat(64), null)).join();
        }
        AuthToken used = tokens.findUsableByAccountAndType(accountId, TokenType.TOTP_RECOVERY,
                Instant.now()).join().get(0);
        tokens.markUsed(used.id(), Instant.now(), null).join();

        assertThat(tokens.countUsable(accountId, TokenType.TOTP_RECOVERY, Instant.now()).join())
                .isEqualTo(4);
    }

    // ================================================================ история и аудит

    @Test
    void история_входов_пишется_и_читается_от_новых_к_старым() {
        long accountId = persist("Steve");
        history.insert(LoginAttempt.failure(accountId, "Steve", LoginResultType.BAD_PASSWORD,
                IpAddress.of("203.0.113.7"), EventSource.MINECRAFT)).join();
        history.insert(LoginAttempt.success(accountId, null, "Steve",
                IpAddress.of("203.0.113.7"), EventSource.MINECRAFT, TwoFactorMethod.TOTP)).join();

        List<LoginAttempt> found = history.findByAccount(accountId, 10, 0).join();

        assertThat(found).hasSize(2);
        assertThat(found.get(0).success()).isTrue();
        assertThat(found.get(0).twoFactorMethod()).isEqualTo(TwoFactorMethod.TOTP);
        assertThat(history.countFailedSince(accountId, Instant.now().minusSeconds(60)).join())
                .isEqualTo(1);
    }

    @Test
    void неудачные_попытки_с_несуществующим_ником_тоже_пишутся() {
        // Именно эти записи дают картину перебора имён.
        history.insert(LoginAttempt.failure(null, "НетТакого",
                LoginResultType.UNKNOWN_ACCOUNT, IpAddress.of("198.51.100.9"),
                EventSource.MINECRAFT)).join();

        assertThat(history.countFailedFromIpSince(IpAddress.of("198.51.100.9"),
                Instant.now().minusSeconds(60)).join()).isEqualTo(1);
    }

    @Test
    void слишком_длинный_ник_не_теряет_запись_о_попытке() {
        // Ник длиннее 16 символов приходит от подменённого клиента. Потерять из-за
        // него запись — значит потерять именно то свидетельство, ради которого
        // история и ведётся.
        history.insert(LoginAttempt.failure(null, "А".repeat(200),
                LoginResultType.UNKNOWN_ACCOUNT, IpAddress.of("198.51.100.77"),
                EventSource.MINECRAFT)).join();

        List<LoginAttempt> found = history.findByIp(IpAddress.of("198.51.100.77"),
                Instant.now().minusSeconds(60), 10).join();
        assertThat(found).singleElement()
                .satisfies(attempt -> assertThat(attempt.usernameAttempt()).hasSize(16));
    }

    @Test
    void пакетная_запись_аудита_работает() {
        long accountId = persist("Steve");
        List<SecurityLogEntry> entries = List.of(
                SecurityLogEntry.of(accountId, SecurityEventType.LOGIN_SUCCESS,
                        EventSource.MINECRAFT, IpAddress.of("203.0.113.7"), "вход"),
                SecurityLogEntry.of(accountId, SecurityEventType.SESSION_CREATED,
                        EventSource.MINECRAFT, IpAddress.of("203.0.113.7"), "сессия"),
                SecurityLogEntry.of(accountId, SecurityEventType.BRUTE_FORCE_DETECTED,
                        EventSource.MINECRAFT, IpAddress.of("203.0.113.7"), "инцидент"));

        audit.insertBatch(entries).join();

        assertThat(audit.findByAccount(accountId, 10, 0).join()).hasSize(3);
        assertThat(audit.findByAccountAndSeverity(accountId, Severity.CRITICAL, 10, 0).join())
                .singleElement()
                .satisfies(entry -> assertThat(entry.eventType())
                        .isEqualTo(SecurityEventType.BRUTE_FORCE_DETECTED.name()));
    }

    @Test
    void ротация_аудита_щадит_WARNING_и_CRITICAL() {
        long accountId = persist("Steve");
        audit.insertBatch(List.of(
                SecurityLogEntry.of(accountId, SecurityEventType.LOGIN_SUCCESS,
                        EventSource.MINECRAFT, null, "info"),
                SecurityLogEntry.of(accountId, SecurityEventType.BRUTE_FORCE_DETECTED,
                        EventSource.MINECRAFT, null, "critical"))).join();

        audit.deleteInfoBefore(Instant.now().plusSeconds(60)).join();

        List<SecurityLogEntry> remaining = audit.findByAccount(accountId, 10, 0).join();
        assertThat(remaining).singleElement()
                .satisfies(entry -> assertThat(entry.severity()).isEqualTo(Severity.CRITICAL));
    }

    @Test
    void метаданные_JSON_переживают_цикл_записи_и_чтения() {
        long accountId = persist("Steve");
        audit.insert(SecurityLogEntry.of(accountId, SecurityEventType.LOGIN_SUCCESS,
                        EventSource.WEB, null, "вход")
                .withMetadata(java.util.Map.of("browser", "Firefox", "attempts", 3))).join();

        SecurityLogEntry loaded = audit.findByAccount(accountId, 1, 0).join().get(0);

        assertThat(loaded.metadata()).containsEntry("browser", "Firefox");
        assertThat(loaded.metadata()).containsEntry("attempts", 3);
    }

    // ================================================================ привязки

    @Test
    void почта_привязывается_и_подтверждается() {
        long accountId = persist("Steve");
        EmailBinding saved = emails.insert(
                EmailBinding.pending(accountId, "Steve@Example.COM", true)).join();

        assertThat(saved.emailLower()).isEqualTo("steve@example.com");
        assertThat(emails.markVerified(saved.id(), Instant.now()).join()).isTrue();
        // Повторное подтверждение уже ничего не меняет.
        assertThat(emails.markVerified(saved.id(), Instant.now()).join()).isFalse();
        assertThat(emails.findPrimary(accountId).join().orElseThrow().verified()).isTrue();
    }

    @Test
    void смена_основного_адреса_атомарна() {
        long accountId = persist("Steve");
        emails.insert(EmailBinding.pending(accountId, "first@example.com", true)).join();
        EmailBinding second = emails.insert(
                EmailBinding.pending(accountId, "second@example.com", false)).join();

        emails.setPrimary(accountId, second.id()).join();

        assertThat(emails.findPrimary(accountId).join().orElseThrow().emailLower())
                .isEqualTo("second@example.com");
        assertThat(emails.findByAccount(accountId).join()).hasSize(2);
    }

    @Test
    void телеграм_привязывается_один_к_одному() {
        long accountId = persist("Steve");
        telegram.insert(TelegramBinding.create(accountId, 555001L, 555001L)).join();

        assertThat(telegram.findByTelegramId(555001L).join()).isPresent();
        assertThat(telegram.findByAccount(accountId).join()).isPresent();

        // Тот же Telegram нельзя привязать ко второму аккаунту: иначе второй фактор
        // превратился бы в общий ключ.
        long other = persist("Alex");
        assertThatThrownBy(() ->
                telegram.insert(TelegramBinding.create(other, 555001L, 555001L)).join())
                .hasCauseInstanceOf(SqlExecutor.DuplicateKeyException.class);
    }

    @Test
    void секрет_TOTP_шифруется_в_базе_и_расшифровывается_обратно() {
        long accountId = persist("Steve");
        totp.insert(TotpSecret.pending(accountId, "JBSWY3DPEHPK3PXP")).join();

        assertThat(totp.findByAccount(accountId).join().orElseThrow().secret())
                .isEqualTo("JBSWY3DPEHPK3PXP");

        // В колонке лежит шифротекст, а не Base32.
        String stored = sql.queryOne("SELECT HEX(secret) AS s FROM totp WHERE account_id = ?",
                rs -> rs.getString("s"), accountId).join().orElseThrow();
        assertThat(stored).doesNotContain("4A42535759");
    }

    @Test
    void одно_временное_окно_TOTP_принимается_только_один_раз() {
        // Защита от повторного использования перехваченного шестизначного кода.
        long accountId = persist("Steve");
        totp.insert(TotpSecret.pending(accountId, "JBSWY3DPEHPK3PXP")).join();

        List<Boolean> results = IntStream.range(0, 20).parallel()
                .mapToObj(i -> totp.compareAndSetCounter(accountId, 100L).join())
                .toList();

        assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(1);
        // Более раннее окно уже не принимается.
        assertThat(totp.compareAndSetCounter(accountId, 99L).join()).isFalse();
        assertThat(totp.compareAndSetCounter(accountId, 101L).join()).isTrue();
    }

    // ================================================================ CAPTCHA

    @Test
    void челлендж_сохраняется_и_находится_по_игроку() {
        UUID playerUuid = UUID.randomUUID();
        CaptchaChallenge issued = captcha.insert(CaptchaChallenge.issue(null, playerUuid,
                CaptchaType.GUI_GRID, "f".repeat(64), IpAddress.of("203.0.113.7"),
                3, Duration.ofMinutes(2))).join();

        assertThat(captcha.findByChallengeId(issued.challengeId()).join()).isPresent();
        assertThat(captcha.findPendingByPlayer(playerUuid, Instant.now()).join()).isPresent();
    }

    @Test
    void просроченные_челленджи_помечаются() {
        UUID playerUuid = UUID.randomUUID();
        captcha.insert(CaptchaChallenge.issue(null, playerUuid, CaptchaType.TEXT_INPUT,
                "f".repeat(64), IpAddress.of("203.0.113.7"), 3, Duration.ofMillis(1))).join();

        assertThat(captcha.expireOverdue(Instant.now().plusSeconds(5)).join())
                .isGreaterThanOrEqualTo(1);
        assertThat(captcha.findPendingByPlayer(playerUuid, Instant.now()).join()).isEmpty();
    }

    // ================================================================ RBAC

    @Test
    void роли_загружаются_вместе_с_правами_одним_запросом() {
        List<net.kofnetwork.auth.api.model.Role> all = roles.findAllWithPermissions().join();

        assertThat(all).hasSize(7);
        var player = all.stream().filter(r -> r.name().equals("player")).findFirst().orElseThrow();
        assertThat(player.permissions()).hasSize(8);
        assertThat(player.hasPermission("kofauth.login")).isTrue();
        assertThat(player.hasPermission("kofauth.admin")).isFalse();

        var owner = all.stream().filter(r -> r.name().equals("owner")).findFirst().orElseThrow();
        assertThat(owner.permissions()).hasSize(24);
    }

    @Test
    void роль_по_умолчанию_определена_ровно_одна() {
        assertThat(roles.findDefaultRole().join()).isPresent()
                .get()
                .satisfies(role -> assertThat(role.name()).isEqualTo("player"));
    }

    @Test
    void временная_роль_исчезает_после_истечения() {
        long accountId = persist("Steve");
        int vipId = roles.findByName("vip").join().orElseThrow().id();

        roles.grantRole(accountId, vipId, null, Instant.now().plusSeconds(3600)).join();
        assertThat(roles.findRolesOfAccount(accountId, Instant.now()).join()).hasSize(1);

        // Через час роль уже не действует.
        assertThat(roles.findRolesOfAccount(accountId, Instant.now().plusSeconds(7200)).join())
                .isEmpty();
    }

    @Test
    void истёкшие_выдачи_ролей_удаляются_планировщиком() {
        long accountId = persist("Steve");
        int vipId = roles.findByName("vip").join().orElseThrow().id();
        roles.grantRole(accountId, vipId, null, Instant.now().minusSeconds(60)).join();

        assertThat(roles.purgeExpiredGrants(Instant.now()).join()).isEqualTo(1);
    }

    // ================================================================ настройки и серверы

    @Test
    void настройки_читаются_и_обновляются() {
        assertThat(settings.get("auth.registration.enabled").join()).contains("true");

        settings.set("auth.registration.enabled", "false", null).join();

        assertThat(settings.get("auth.registration.enabled").join()).contains("false");
        assertThat(settings.getAll().join()).containsKey("captcha.type");
    }

    @Test
    void служебные_настройки_нельзя_удалить() {
        // schema.version помечен editable = 0.
        assertThat(settings.delete("schema.version").join()).isFalse();
        assertThat(settings.get("schema.version").join()).isPresent();
    }

    @Test
    void доступные_серверы_фильтруются_по_свежести_heartbeat() {
        Instant now = Instant.now();
        servers.register(new ServerNode(0, "limbo-1", ServerType.LIMBO, "127.0.0.1", 30001,
                null, true, 5, 100, 0, now, now)).join();
        servers.register(new ServerNode(0, "limbo-2", ServerType.LIMBO, "127.0.0.1", 30002,
                null, true, 1, 100, 0, now.minus(Duration.ofMinutes(5)), now)).join();

        List<ServerNode> available = servers.findAvailable(ServerType.LIMBO, now).join();

        // limbo-2 «онлайн» по флагу, но heartbeat протух — процесс умер, не сняв флаг.
        assertThat(available).singleElement()
                .satisfies(node -> assertThat(node.name()).isEqualTo("limbo-1"));
    }

    @Test
    void серверы_сортируются_по_загрузке_а_не_по_числу_игроков() {
        // 20 из 50 занят сильнее, чем 30 из 200.
        Instant now = Instant.now();
        servers.register(new ServerNode(0, "small", ServerType.LOBBY, "127.0.0.1", 30003,
                null, true, 20, 50, 0, now, now)).join();
        servers.register(new ServerNode(0, "big", ServerType.LOBBY, "127.0.0.1", 30004,
                null, true, 30, 200, 0, now, now)).join();

        assertThat(servers.findAvailable(ServerType.LOBBY, now).join())
                .extracting(ServerNode::name)
                .containsExactly("big", "small");
    }

    @Test
    void переполненный_сервер_не_предлагается() {
        Instant now = Instant.now();
        servers.register(new ServerNode(0, "full", ServerType.LOBBY, "127.0.0.1", 30005,
                null, true, 100, 100, 0, now, now)).join();

        assertThat(servers.findAvailable(ServerType.LOBBY, now).join()).isEmpty();
    }

    // ================================================================ каскады

    @Test
    void удаление_аккаунта_убирает_связанные_данные_но_сохраняет_аудит() {
        long accountId = persist("Steve");
        sessions.insert(Session.create(accountId, null, SessionType.GAME,
                IpAddress.of("203.0.113.7"), null, Duration.ofHours(1), Duration.ofDays(7))).join();
        tokens.insert(AuthToken.issue(accountId, TokenType.REFRESH, "9".repeat(64), null)).join();
        emails.insert(EmailBinding.pending(accountId, "steve@example.com", true)).join();
        audit.insert(SecurityLogEntry.of(accountId, SecurityEventType.BRUTE_FORCE_DETECTED,
                EventSource.MINECRAFT, null, "инцидент")).join();

        assertThat(accounts.delete(accountId).join()).isTrue();

        assertThat(sessions.findActiveByAccount(accountId, Instant.now()).join()).isEmpty();
        assertThat(tokens.findByHash("9".repeat(64)).join()).isEmpty();
        assertThat(emails.findByAccount(accountId).join()).isEmpty();
        // Аудит переживает удаление аккаунта: иначе исчезли бы следы инцидента.
        assertThat(audit.findByEventType(SecurityEventType.BRUTE_FORCE_DETECTED.name(),
                Instant.now().minusSeconds(60), 10).join()).isNotEmpty();
    }

    // ================================================================ целостность аудита

    /**
     * Действие администратора из консоли.
     *
     * <p>Ровно тот запрос, который отвергался внешним ключом
     * {@code fk_security_logs_actor}: исполнитель обозначен нулём, потому что у
     * консоли аккаунта нет. Ноль обязан превратиться в {@code NULL}, а запись —
     * дойти до базы.
     */
    @Test
    void действие_из_консоли_записывается_без_исполнителя() {
        long accountId = persist("Steve");

        audit.insert(SecurityLogEntry.byAdmin(accountId, 0L,
                SecurityEventType.ACCOUNT_LOCKED, EventSource.SYSTEM, "из консоли")).join();

        assertThat(audit.findByAccount(accountId, 10, 0).join()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.actorId()).isNull();
                    assertThat(entry.message()).isEqualTo("из консоли");
                });
    }

    /**
     * Пакет с одной негодной строкой.
     *
     * <p>Пакет — один запрос, и база отвергает его целиком. Раньше это означало,
     * что одна строка с несуществующим исполнителем уносила с собой все соседние
     * записи пачки — события, к действию администратора отношения не имевшие.
     * Здесь негодная строка теряется одна, а остальные доходят.
     */
    @Test
    void негодная_строка_не_уносит_остальной_пакет_аудита() {
        long accountId = persist("Steve");
        long missingActor = accountId + 100_000L;

        audit.insertBatch(List.of(
                SecurityLogEntry.of(accountId, SecurityEventType.LOGIN_SUCCESS,
                        EventSource.MINECRAFT, IpAddress.of("203.0.113.7"), "до"),
                SecurityLogEntry.byAdmin(accountId, missingActor,
                        SecurityEventType.ACCOUNT_LOCKED, EventSource.SYSTEM, "негодная"),
                SecurityLogEntry.of(accountId, SecurityEventType.BRUTE_FORCE_DETECTED,
                        EventSource.MINECRAFT, IpAddress.of("203.0.113.7"), "после"))).join();

        assertThat(audit.findByAccount(accountId, 10, 0).join())
                .extracting(SecurityLogEntry::message)
                .containsExactlyInAnyOrder("до", "после");
    }

    /** Успешный пакет по-прежнему уходит одним запросом и ничего не теряет. */
    @Test
    void исправный_пакет_аудита_записывается_целиком() {
        long accountId = persist("Steve");

        audit.insertBatch(IntStream.range(0, 25)
                .mapToObj(i -> SecurityLogEntry.of(accountId, SecurityEventType.LOGIN_SUCCESS,
                        EventSource.MINECRAFT, IpAddress.of("203.0.113.7"), "вход " + i))
                .toList()).join();

        assertThat(audit.findByAccount(accountId, 100, 0).join()).hasSize(25);
    }

    // ================================================================ очередь ботов

    /**
     * Код привязки в служебном канале.
     *
     * <p>Вид сообщения {@code LINK_CODE} существует в коде с появления команды
     * {@code /discord}, но в перечислении колонки его не было: вставка отвергалась,
     * и публикация кода не работала ни разу. Проверяется именно запись и чтение
     * обратно — миграция V4 добавляет значение в конец списка.
     */
    @Test
    void очередь_ботов_принимает_код_привязки() {
        Instant now = Instant.now();

        outbox.append(new net.kofnetwork.auth.api.model.BotMessage(0L,
                net.kofnetwork.auth.api.model.BotPlatform.DISCORD, 0L, 0L,
                net.kofnetwork.auth.api.model.BotMessage.Kind.LINK_CODE,
                java.util.Map.of("code", "ABCD1234", "username", "Steve", "ttl", "10 мин"),
                now, now.plusSeconds(600))).join();

        assertThat(outbox.readAfter(net.kofnetwork.auth.api.model.BotPlatform.DISCORD,
                        0L, 10, now).join())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.kind())
                            .isEqualTo(net.kofnetwork.auth.api.model.BotMessage.Kind.LINK_CODE);
                    assertThat(message.get("code")).isEqualTo("ABCD1234");
                });
    }

    /** Все виды сообщений обязаны проходить в колонку: перечисления должны совпадать. */
    @Test
    void очередь_ботов_принимает_все_виды_сообщений() {
        Instant now = Instant.now();
        for (net.kofnetwork.auth.api.model.BotMessage.Kind kind
                : net.kofnetwork.auth.api.model.BotMessage.Kind.values()) {
            outbox.append(new net.kofnetwork.auth.api.model.BotMessage(0L,
                    net.kofnetwork.auth.api.model.BotPlatform.TELEGRAM, 1L, 1L, kind,
                    java.util.Map.of(), now, now.plusSeconds(600))).join();
        }

        assertThat(outbox.readAfter(net.kofnetwork.auth.api.model.BotPlatform.TELEGRAM,
                        0L, 50, now).join())
                .extracting(net.kofnetwork.auth.api.model.BotMessage::kind)
                .containsExactlyInAnyOrder(net.kofnetwork.auth.api.model.BotMessage.Kind.values());
    }

    /** История входов обязана принимать все источники, включая системный. */
    @Test
    void история_входов_принимает_системный_источник() {
        long accountId = persist("Steve");

        history.insert(LoginAttempt.failure(accountId, "Steve", LoginResultType.ERROR,
                IpAddress.of("203.0.113.7"), EventSource.SYSTEM)).join();

        assertThat(history.findByAccount(accountId, 10, 0).join()).singleElement()
                .satisfies(attempt -> assertThat(attempt.source()).isEqualTo(EventSource.SYSTEM));
    }

    // ================================================================ подтверждения входа

    /**
     * Две одновременные попытки одного аккаунта.
     *
     * <p>Прежнее условие «погасить всё, кроме моей» было симметричным: каждая из
     * двух попыток объявляла устаревшей другую, и живой не оставалось ни одной —
     * игрок получал две мёртвые кнопки и не мог войти ни одним способом. Граница
     * по номеру записи задаёт одинаковый для обоих запросов порядок: выживает
     * последняя записанная.
     */
    @Test
    void из_двух_одновременных_подтверждений_выживает_последнее() {
        long accountId = persist("Steve");
        Instant now = Instant.now();

        net.kofnetwork.auth.api.model.LoginApproval first =
                approvals.insert(approval(accountId, "first", "attempt-1", now)).join();
        net.kofnetwork.auth.api.model.LoginApproval second =
                approvals.insert(approval(accountId, "second", "attempt-2", now)).join();

        // Обе попытки гасят «всё, что старше себя» — в любом порядке.
        approvals.supersedePending(accountId, first.id(), now).join();
        approvals.supersedePending(accountId, second.id(), now).join();

        assertThat(approvals.findByPublicId("first").join()).get()
                .extracting(net.kofnetwork.auth.api.model.LoginApproval::status)
                .isEqualTo(net.kofnetwork.auth.api.model.ApprovalStatus.EXPIRED);
        assertThat(approvals.findByPublicId("second").join()).get()
                .extracting(net.kofnetwork.auth.api.model.LoginApproval::status)
                .as("последняя попытка обязана остаться живой")
                .isEqualTo(net.kofnetwork.auth.api.model.ApprovalStatus.PENDING);
    }

    private static net.kofnetwork.auth.api.model.LoginApproval approval(long accountId,
                                                                        String publicId,
                                                                        String attemptId,
                                                                        Instant now) {
        return new net.kofnetwork.auth.api.model.LoginApproval(0L, publicId, accountId, "Steve",
                UUID.randomUUID(), attemptId, EventSource.MINECRAFT, DevicePlatform.MINECRAFT,
                null, null, net.kofnetwork.auth.api.model.BotPlatform.TELEGRAM, 100L, 100L,
                IpAddress.of("203.0.113.7"), null, null, null,
                net.kofnetwork.auth.api.model.ApprovalStatus.PENDING,
                now, now.plusSeconds(120), null, null, null, null, null);
    }
}
