package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.AccountStatus;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import net.kofnetwork.auth.api.repository.AccountRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link AccountRepository} на JDBC. */
public final class JdbcAccountRepository implements AccountRepository {

    /**
     * Полный набор колонок. Вынесен в константу, чтобы порядок и состав не разъезжались
     * между запросами: {@code SELECT *} здесь неприемлем — он ломается при добавлении
     * колонки и тянет по сети то, что не нужно.
     */
    private static final String COLUMNS = """
            id, uuid, username, lower_username, password_hash, password_algorithm,
            password_updated_at, status, premium, registration_ip, registration_date,
            last_login_ip, last_login_at, last_logout_at, last_server, last_country,
            last_city, last_user_agent, failed_login_attempts, locked_until,
            captcha_passed, two_factor_methods, created_at, updated_at
            """;

    private final SqlExecutor sql;

    public JdbcAccountRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    /** Отображает строку в {@link Account}. */
    static @NotNull Account map(@NotNull ResultSet rs) throws SQLException {
        return Account.builder()
                .id(rs.getLong("id"))
                .uuid(SqlTypes.readRequiredUuid(rs, "uuid"))
                .username(rs.getString("username"))
                .lowerUsername(rs.getString("lower_username"))
                .passwordHash(rs.getString("password_hash"))
                .passwordAlgorithm(rs.getString("password_algorithm"))
                .passwordUpdatedAt(SqlTypes.readInstant(rs, "password_updated_at"))
                .status(SqlTypes.readEnum(rs, "status", AccountStatus.class, AccountStatus.ACTIVE))
                .premium(rs.getBoolean("premium"))
                .registrationIp(SqlTypes.readRequiredIp(rs, "registration_ip"))
                .registrationDate(SqlTypes.readRequiredInstant(rs, "registration_date"))
                .lastLoginIp(SqlTypes.readIp(rs, "last_login_ip"))
                .lastLoginAt(SqlTypes.readInstant(rs, "last_login_at"))
                .lastLogoutAt(SqlTypes.readInstant(rs, "last_logout_at"))
                .lastServer(rs.getString("last_server"))
                .lastCountry(rs.getString("last_country"))
                .lastCity(rs.getString("last_city"))
                .lastUserAgent(rs.getString("last_user_agent"))
                .failedLoginAttempts(rs.getInt("failed_login_attempts"))
                .lockedUntil(SqlTypes.readInstant(rs, "locked_until"))
                .captchaPassed(rs.getBoolean("captcha_passed"))
                .twoFactorMethods(SqlTypes.readEnumSet(rs, "two_factor_methods", TwoFactorMethod.class))
                .createdAt(SqlTypes.readRequiredInstant(rs, "created_at"))
                .updatedAt(SqlTypes.readRequiredInstant(rs, "updated_at"))
                .build();
    }

    @Override
    public @NotNull CompletableFuture<Optional<Account>> findByUsername(@NotNull String username) {
        return sql.queryOne(
                "SELECT " + COLUMNS + " FROM users WHERE lower_username = ?",
                JdbcAccountRepository::map,
                username.toLowerCase(Locale.ROOT));
    }

    @Override
    public @NotNull CompletableFuture<Optional<Account>> findByUuid(@NotNull UUID uuid) {
        return sql.queryOne(
                "SELECT " + COLUMNS + " FROM users WHERE uuid = ?",
                JdbcAccountRepository::map,
                (Object) SqlTypes.uuidToBytes(uuid));
    }

    @Override
    public @NotNull CompletableFuture<Optional<Account>> findById(long id) {
        return sql.queryOne(
                "SELECT " + COLUMNS + " FROM users WHERE id = ?",
                JdbcAccountRepository::map,
                id);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> existsByUsername(@NotNull String username) {
        return sql.queryInt("SELECT 1 FROM users WHERE lower_username = ?", 0,
                        username.toLowerCase(Locale.ROOT))
                .thenApply(found -> found == 1);
    }

    @Override
    public @NotNull CompletableFuture<Account> insert(@NotNull Account account) {
        return sql.insertReturningKey("""
                        INSERT INTO users (uuid, username, lower_username, password_hash,
                                           password_algorithm, password_updated_at, status, premium,
                                           registration_ip, registration_date, captcha_passed,
                                           two_factor_methods, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                SqlTypes.uuidToBytes(account.uuid()),
                account.username(),
                account.lowerUsername(),
                account.passwordHash(),
                account.passwordAlgorithm(),
                SqlTypes.toTimestamp(account.passwordUpdatedAt()),
                account.status().name(),
                account.premium(),
                SqlTypes.ipToBytes(account.registrationIp()),
                SqlTypes.toTimestamp(account.registrationDate()),
                account.captchaPassed(),
                SqlTypes.enumSetToString(account.twoFactorMethods()),
                SqlTypes.toTimestamp(account.createdAt()),
                SqlTypes.toTimestamp(account.updatedAt())
        ).thenApply(id -> account.toBuilder().id(id).build());
    }

    @Override
    public @NotNull CompletableFuture<Account> update(@NotNull Account account) {
        return sql.update("""
                        UPDATE users SET
                            username = ?, lower_username = ?, password_hash = ?, password_algorithm = ?,
                            password_updated_at = ?, status = ?, premium = ?, last_login_ip = ?,
                            last_login_at = ?, last_logout_at = ?, last_server = ?, last_country = ?,
                            last_city = ?, last_user_agent = ?, failed_login_attempts = ?,
                            locked_until = ?, captcha_passed = ?, two_factor_methods = ?
                        WHERE id = ?
                        """,
                account.username(),
                account.lowerUsername(),
                account.passwordHash(),
                account.passwordAlgorithm(),
                SqlTypes.toTimestamp(account.passwordUpdatedAt()),
                account.status().name(),
                account.premium(),
                SqlTypes.ipToBytes(account.lastLoginIp()),
                SqlTypes.toTimestamp(account.lastLoginAt()),
                SqlTypes.toTimestamp(account.lastLogoutAt()),
                account.lastServer(),
                account.lastCountry(),
                account.lastCity(),
                account.lastUserAgent(),
                account.failedLoginAttempts(),
                SqlTypes.toTimestamp(account.lockedUntil()),
                account.captchaPassed(),
                SqlTypes.enumSetToString(account.twoFactorMethods()),
                account.id()
        ).thenApply(ignored -> account);
    }

    @Override
    public @NotNull CompletableFuture<Void> updateLastLogin(long accountId,
                                                            @NotNull IpAddress ip,
                                                            @NotNull Instant at,
                                                            @Nullable String server,
                                                            @Nullable String country,
                                                            @Nullable String city,
                                                            @Nullable String userAgent) {
        // Точечный UPDATE вместо полного: это самая частая запись в системе, и
        // переписывать ради неё двадцать колонок значит нагружать binlog и репликацию.
        // Заодно снимается риск затереть чужое изменение данными, прочитанными минуту назад.
        return sql.update("""
                        UPDATE users SET
                            last_login_ip = ?, last_login_at = ?, last_server = ?,
                            last_country = ?, last_city = ?, last_user_agent = ?,
                            failed_login_attempts = 0, locked_until = NULL
                        WHERE id = ?
                        """,
                SqlTypes.ipToBytes(ip),
                SqlTypes.toTimestamp(at),
                server, country, city, userAgent,
                accountId
        ).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> updateLastLogout(long accountId, @NotNull Instant at) {
        return sql.update("UPDATE users SET last_logout_at = ? WHERE id = ?",
                SqlTypes.toTimestamp(at), accountId).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> updatePassword(long accountId,
                                                           @NotNull String passwordHash,
                                                           @NotNull String algorithm,
                                                           @NotNull Instant at) {
        return sql.update("""
                        UPDATE users SET password_hash = ?, password_algorithm = ?,
                                         password_updated_at = ?
                        WHERE id = ?
                        """,
                passwordHash, algorithm, SqlTypes.toTimestamp(at), accountId
        ).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> updateStatus(long accountId, @NotNull AccountStatus status) {
        return sql.update("UPDATE users SET status = ? WHERE id = ?", status.name(), accountId)
                .thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Integer> incrementFailedAttempts(long accountId) {
        // Инкремент выполняется на стороне базы. Чтение с последующей записью здесь
        // недопустимо: при параллельном переборе с нескольких соединений часть
        // инкрементов терялась бы, и лимит попыток перестал бы срабатывать.
        return sql.withConnectionAsync(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE users SET failed_login_attempts = failed_login_attempts + 1 WHERE id = ?")) {
                statement.setLong(1, accountId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT failed_login_attempts FROM users WHERE id = ?")) {
                statement.setLong(1, accountId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<Void> resetFailedAttempts(long accountId) {
        return sql.update(
                "UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?",
                accountId).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> lockUntil(long accountId, @NotNull Instant until) {
        return sql.update("UPDATE users SET locked_until = ? WHERE id = ?",
                SqlTypes.toTimestamp(until), accountId).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> markCaptchaPassed(long accountId, boolean passed) {
        return sql.update("UPDATE users SET captcha_passed = ? WHERE id = ?", passed, accountId)
                .thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> updateTwoFactorMethods(long accountId,
                                                                   @NotNull Set<TwoFactorMethod> methods) {
        return sql.update("UPDATE users SET two_factor_methods = ? WHERE id = ?",
                SqlTypes.enumSetToString(methods), accountId).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Integer> countRegistrationsFromIp(@NotNull IpAddress ip,
                                                                        @NotNull Instant since) {
        return sql.queryInt(
                "SELECT COUNT(*) FROM users WHERE registration_ip = ? AND registration_date >= ?",
                0,
                SqlTypes.ipToBytes(ip), SqlTypes.toTimestamp(since));
    }

    @Override
    public @NotNull CompletableFuture<List<Account>> searchByUsernamePrefix(@NotNull String prefix,
                                                                            int limit) {
        // LIKE с подстановкой только справа использует индекс uk_users_lower_username;
        // ведущий '%' превратил бы запрос в полное сканирование таблицы.
        return sql.queryList(
                "SELECT " + COLUMNS + " FROM users WHERE lower_username LIKE ? ORDER BY lower_username LIMIT ?",
                JdbcAccountRepository::map,
                escapeLike(prefix.toLowerCase(Locale.ROOT)) + "%",
                Math.max(1, Math.min(limit, 100)));
    }

    @Override
    public @NotNull CompletableFuture<List<Account>> findPageAfter(long afterId, int limit) {
        // Условие по первичному ключу с сортировкой по нему же — обычный range scan,
        // стоимость которого не зависит от того, насколько далеко ушёл курсор.
        return sql.queryList(
                "SELECT " + COLUMNS + " FROM users WHERE id > ? ORDER BY id LIMIT ?",
                JdbcAccountRepository::map,
                afterId,
                Math.max(1, Math.min(limit, 1000)));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(long accountId) {
        return sql.update("DELETE FROM users WHERE id = ?", accountId)
                .thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<Long> count() {
        return sql.queryLong("SELECT COUNT(*) FROM users", 0L);
    }

    /**
     * Экранирует спецсимволы {@code LIKE}.
     *
     * <p>Без этого ник {@code %} в автодополнении выбрал бы всю таблицу: параметризация
     * защищает от инъекции, но не от того, что значение само по себе является шаблоном.
     */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
