package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.ServerNode;
import net.kofnetwork.auth.api.model.ServerType;
import net.kofnetwork.auth.api.repository.ServerRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link ServerRepository} на JDBC. */
public final class JdbcServerRepository implements ServerRepository {

    private static final String COLUMNS = """
            id, name, type, address, port, motd, online, player_count, max_players,
            priority, last_heartbeat_at, registered_at
            """;

    private final SqlExecutor sql;

    public JdbcServerRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull ServerNode map(@NotNull ResultSet rs) throws SQLException {
        return new ServerNode(
                rs.getInt("id"),
                rs.getString("name"),
                SqlTypes.readEnum(rs, "type", ServerType.class, ServerType.GAME),
                rs.getString("address"),
                rs.getInt("port"),
                rs.getString("motd"),
                rs.getBoolean("online"),
                rs.getInt("player_count"),
                rs.getInt("max_players"),
                rs.getInt("priority"),
                SqlTypes.readInstant(rs, "last_heartbeat_at"),
                SqlTypes.readRequiredInstant(rs, "registered_at"));
    }

    @Override
    public @NotNull CompletableFuture<ServerNode> register(@NotNull ServerNode server) {
        return sql.withConnectionAsync(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO servers (name, type, address, port, motd, online, player_count,
                                         max_players, priority, last_heartbeat_at, registered_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE type = VALUES(type), address = VALUES(address),
                                            port = VALUES(port), motd = VALUES(motd),
                                            online = VALUES(online), priority = VALUES(priority),
                                            last_heartbeat_at = VALUES(last_heartbeat_at)
                    """)) {
                statement.setString(1, server.name());
                statement.setString(2, server.type().name());
                statement.setString(3, server.address());
                statement.setInt(4, server.port());
                statement.setString(5, server.motd());
                statement.setBoolean(6, server.online());
                statement.setInt(7, server.playerCount());
                statement.setInt(8, server.maxPlayers());
                statement.setInt(9, server.priority());
                statement.setTimestamp(10, SqlTypes.toTimestamp(server.lastHeartbeatAt()));
                statement.setTimestamp(11, SqlTypes.toTimestamp(server.registeredAt()));
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM servers WHERE name = ?")) {
                statement.setString(1, server.name());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("Сервер не найден сразу после регистрации");
                    }
                    return map(rs);
                }
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<Optional<ServerNode>> findByName(@NotNull String name) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM servers WHERE name = ?",
                JdbcServerRepository::map, name);
    }

    @Override
    public @NotNull CompletableFuture<List<ServerNode>> findByType(@NotNull ServerType type) {
        return sql.queryList(
                "SELECT " + COLUMNS + " FROM servers WHERE type = ? ORDER BY priority, name",
                JdbcServerRepository::map, type.name());
    }

    @Override
    public @NotNull CompletableFuture<List<ServerNode>> findAll() {
        return sql.queryList("SELECT " + COLUMNS + " FROM servers ORDER BY type, priority, name",
                JdbcServerRepository::map, new Object[0]);
    }

    @Override
    public @NotNull CompletableFuture<List<ServerNode>> findAvailable(@NotNull ServerType type,
                                                                      @NotNull Instant at) {
        // Свежесть heartbeat проверяется в SQL: иначе прокси вычитывал бы весь
        // реестр на каждом подключении, чтобы отбросить мёртвые узлы.
        //
        // Сортировка по загрузке, а не по абсолютному числу игроков: сервер на 20
        // из 50 занят сильнее, чем на 30 из 200.
        Instant threshold = at.minus(ServerNode.HEARTBEAT_TIMEOUT);
        return sql.queryList("""
                        SELECT %s FROM servers
                        WHERE type = ? AND online = 1 AND last_heartbeat_at > ?
                          AND (max_players = 0 OR player_count < max_players)
                        ORDER BY priority,
                                 CASE WHEN max_players = 0 THEN 0
                                      ELSE player_count / max_players END,
                                 name
                        """.formatted(COLUMNS),
                JdbcServerRepository::map,
                type.name(), SqlTypes.toTimestamp(threshold));
    }

    @Override
    public @NotNull CompletableFuture<Void> heartbeat(@NotNull String name,
                                                      @NotNull Instant at,
                                                      int playerCount,
                                                      int maxPlayers) {
        return sql.update("""
                        UPDATE servers SET last_heartbeat_at = ?, player_count = ?,
                                           max_players = ?, online = 1
                        WHERE name = ?
                        """,
                SqlTypes.toTimestamp(at), playerCount, maxPlayers, name
        ).thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Void> markOffline(@NotNull String name) {
        return sql.update("UPDATE servers SET online = 0, player_count = 0 WHERE name = ?", name)
                .thenApply(ignored -> null);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull String name) {
        return sql.update("DELETE FROM servers WHERE name = ?", name)
                .thenApply(affected -> affected > 0);
    }
}
