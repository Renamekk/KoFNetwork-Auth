package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.Device;
import net.kofnetwork.auth.api.model.DevicePlatform;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.repository.DeviceRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link DeviceRepository} на JDBC. */
public final class JdbcDeviceRepository implements DeviceRepository {

    private static final String COLUMNS = """
            id, account_id, fingerprint, display_name, platform, operating_system, browser,
            client_brand, protocol_version, first_seen_ip, last_seen_ip, first_seen_at,
            last_seen_at, trusted, trusted_at, blocked, blocked_at
            """;

    private final SqlExecutor sql;

    public JdbcDeviceRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull Device map(@NotNull ResultSet rs) throws SQLException {
        return new Device(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("fingerprint"),
                rs.getString("display_name"),
                SqlTypes.readEnum(rs, "platform", DevicePlatform.class, DevicePlatform.UNKNOWN),
                rs.getString("operating_system"),
                rs.getString("browser"),
                rs.getString("client_brand"),
                SqlTypes.readNullableInt(rs, "protocol_version"),
                SqlTypes.readRequiredIp(rs, "first_seen_ip"),
                SqlTypes.readRequiredIp(rs, "last_seen_ip"),
                SqlTypes.readRequiredInstant(rs, "first_seen_at"),
                SqlTypes.readRequiredInstant(rs, "last_seen_at"),
                rs.getBoolean("trusted"),
                SqlTypes.readInstant(rs, "trusted_at"),
                rs.getBoolean("blocked"),
                SqlTypes.readInstant(rs, "blocked_at"));
    }

    @Override
    public @NotNull CompletableFuture<UpsertResult> findOrCreate(long accountId,
                                                                 @NotNull String fingerprint,
                                                                 @NotNull DevicePlatform platform,
                                                                 @NotNull IpAddress ip,
                                                                 @NotNull Instant at) {
        // Одной операцией вместо «найти, если нет — вставить»: два входа с одного
        // устройства в один момент иначе гонятся за уникальным ключом, и один падает.
        //
        // Признак «создано только что» — количество затронутых строк. При
        // ON DUPLICATE KEY UPDATE MySQL возвращает 1 при вставке, 2 при изменении
        // существующей строки и 0, если обновление ничего не поменяло. Единица
        // означает вставку однозначно, и только она нас интересует.
        //
        // Сравнивать first_seen_at с переданным моментом нельзя: DATETIME(3) хранит
        // миллисекунды, и вставленное значение оказывается не равно исходному
        // Instant, у которого точность до наносекунд.
        return sql.withConnectionAsync(connection -> {
            boolean inserted;
            try (var statement = connection.prepareStatement("""
                    INSERT INTO devices (account_id, fingerprint, platform, first_seen_ip,
                                         last_seen_ip, first_seen_at, last_seen_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE last_seen_ip = VALUES(last_seen_ip),
                                            last_seen_at = VALUES(last_seen_at)
                    """)) {
                statement.setLong(1, accountId);
                statement.setString(2, fingerprint);
                statement.setString(3, platform.name());
                statement.setBytes(4, ip.toBytes());
                statement.setBytes(5, ip.toBytes());
                statement.setTimestamp(6, SqlTypes.toTimestamp(at));
                statement.setTimestamp(7, SqlTypes.toTimestamp(at));
                inserted = statement.executeUpdate() == 1;
            }

            try (var statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM devices WHERE account_id = ? AND fingerprint = ?")) {
                statement.setLong(1, accountId);
                statement.setString(2, fingerprint);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("Устройство не найдено сразу после вставки");
                    }
                    return new UpsertResult(map(rs), inserted);
                }
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<Optional<Device>> findById(long id) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM devices WHERE id = ?",
                JdbcDeviceRepository::map, id);
    }

    @Override
    public @NotNull CompletableFuture<Optional<Device>> findByFingerprint(long accountId,
                                                                          @NotNull String fingerprint) {
        return sql.queryOne(
                "SELECT " + COLUMNS + " FROM devices WHERE account_id = ? AND fingerprint = ?",
                JdbcDeviceRepository::map, accountId, fingerprint);
    }

    @Override
    public @NotNull CompletableFuture<List<Device>> findByAccount(long accountId) {
        return sql.queryList(
                "SELECT " + COLUMNS + " FROM devices WHERE account_id = ? ORDER BY last_seen_at DESC",
                JdbcDeviceRepository::map, accountId);
    }

    @Override
    public @NotNull CompletableFuture<Device> update(@NotNull Device device) {
        return sql.update("""
                        UPDATE devices SET
                            display_name = ?, operating_system = ?, browser = ?, client_brand = ?,
                            protocol_version = ?, last_seen_ip = ?, last_seen_at = ?,
                            trusted = ?, trusted_at = ?, blocked = ?, blocked_at = ?
                        WHERE id = ?
                        """,
                device.displayName(),
                device.operatingSystem(),
                device.browser(),
                device.clientBrand(),
                device.protocolVersion(),
                SqlTypes.ipToBytes(device.lastSeenIp()),
                SqlTypes.toTimestamp(device.lastSeenAt()),
                device.trusted(),
                SqlTypes.toTimestamp(device.trustedAt()),
                device.blocked(),
                SqlTypes.toTimestamp(device.blockedAt()),
                device.id()
        ).thenApply(ignored -> device);
    }

    @Override
    public @NotNull CompletableFuture<Void> setTrusted(long deviceId, boolean trusted, @NotNull Instant at) {
        return sql.update("UPDATE devices SET trusted = ?, trusted_at = ? WHERE id = ?",
                trusted, trusted ? SqlTypes.toTimestamp(at) : null, deviceId
        ).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> setBlocked(long deviceId, boolean blocked, @NotNull Instant at) {
        // Заблокированное устройство перестаёт быть доверенным: иначе снятие
        // блокировки молча вернуло бы ему право обходить второй фактор.
        return sql.update("""
                        UPDATE devices SET blocked = ?, blocked_at = ?,
                                           trusted = CASE WHEN ? THEN 0 ELSE trusted END
                        WHERE id = ?
                        """,
                blocked, blocked ? SqlTypes.toTimestamp(at) : null, blocked, deviceId
        ).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(long deviceId) {
        return sql.update("DELETE FROM devices WHERE id = ?", deviceId)
                .thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<Integer> countByAccount(long accountId) {
        return sql.queryInt("SELECT COUNT(*) FROM devices WHERE account_id = ?", 0, accountId);
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteStale(@NotNull Instant lastSeenBefore) {
        // Доверенные устройства не удаляются по давности: игрок мог не заходить
        // полгода, и потеря доверия заставила бы его проходить 2FA заново.
        return sql.update("DELETE FROM devices WHERE last_seen_at < ? AND trusted = 0",
                SqlTypes.toTimestamp(lastSeenBefore));
    }
}
