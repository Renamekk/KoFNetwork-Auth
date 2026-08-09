package net.kofnetwork.auth.core.repository.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kofnetwork.auth.api.model.AuthToken;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.TokenType;
import net.kofnetwork.auth.api.repository.TokenRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link TokenRepository} на JDBC. */
public final class JdbcTokenRepository implements TokenRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcTokenRepository.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String COLUMNS = """
            id, account_id, session_id, token_hash, type, parent_token_id, issued_ip,
            issued_at, expires_at, used, used_at, used_ip, revoked, revoked_at, metadata
            """;

    private final SqlExecutor sql;

    public JdbcTokenRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull AuthToken map(@NotNull ResultSet rs) throws SQLException {
        return new AuthToken(
                rs.getLong("id"),
                SqlTypes.readNullableLong(rs, "account_id"),
                SqlTypes.readNullableLong(rs, "session_id"),
                rs.getString("token_hash"),
                SqlTypes.readEnum(rs, "type", TokenType.class, TokenType.REFRESH),
                SqlTypes.readNullableLong(rs, "parent_token_id"),
                SqlTypes.readIp(rs, "issued_ip"),
                SqlTypes.readRequiredInstant(rs, "issued_at"),
                SqlTypes.readRequiredInstant(rs, "expires_at"),
                rs.getBoolean("used"),
                SqlTypes.readInstant(rs, "used_at"),
                SqlTypes.readIp(rs, "used_ip"),
                rs.getBoolean("revoked"),
                SqlTypes.readInstant(rs, "revoked_at"),
                readMetadata(rs.getString("metadata")));
    }

    private static Map<String, Object> readMetadata(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            // Повреждённый JSON в одной строке не должен ломать выборку целиком.
            LOGGER.warn("Не удалось разобрать metadata токена: {}", e.getMessage());
            return Map.of();
        }
    }

    private static @Nullable String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(metadata);
        } catch (Exception e) {
            LOGGER.warn("Не удалось сериализовать metadata токена: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public @NotNull CompletableFuture<AuthToken> insert(@NotNull AuthToken token) {
        return sql.insertReturningKey("""
                        INSERT INTO tokens (account_id, session_id, token_hash, type, parent_token_id,
                                            issued_ip, issued_at, expires_at, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                token.accountId(),
                token.sessionId(),
                token.tokenHash(),
                token.type().name(),
                token.parentTokenId(),
                SqlTypes.ipToBytes(token.issuedIp()),
                SqlTypes.toTimestamp(token.issuedAt()),
                SqlTypes.toTimestamp(token.expiresAt()),
                writeMetadata(token.metadata())
        ).thenApply(token::withId);
    }

    @Override
    public @NotNull CompletableFuture<Optional<AuthToken>> findByHash(@NotNull String tokenHash) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM tokens WHERE token_hash = ?",
                JdbcTokenRepository::map, tokenHash);
    }

    @Override
    public @NotNull CompletableFuture<Optional<AuthToken>> findById(long id) {
        return sql.queryOne("SELECT " + COLUMNS + " FROM tokens WHERE id = ?",
                JdbcTokenRepository::map, id);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> markUsed(long tokenId,
                                                        @NotNull Instant at,
                                                        @Nullable IpAddress ip) {
        // Условие used = 0 делает проверку и пометку одной атомарной операцией.
        // Без него два одновременных запроса с одним кодом сброса пароля оба
        // прошли бы проверку «не использован» и оба сработали бы.
        return sql.update("""
                        UPDATE tokens SET used = 1, used_at = ?, used_ip = ?
                        WHERE id = ? AND used = 0
                        """,
                SqlTypes.toTimestamp(at), SqlTypes.ipToBytes(ip), tokenId
        ).thenApply(affected -> affected > 0);
    }

    /**
     * Погашение одним {@code UPDATE}: проверка и пометка неразделимы.
     *
     * <p>Условие содержит всё, что раньше проверялось отдельными чтениями, — тип,
     * {@code used = 0}, {@code revoked = 0} и срок. Между проверкой и пометкой больше
     * нет момента, в который второй запрос увидел бы токен пригодным. При нулевом
     * числе изменённых строк выполняется диагностическое чтение: оно объясняет отказ,
     * но ничего не разрешает.
     *
     * <p>Обе команды идут одним соединением в транзакции, чтобы диагностическое чтение
     * видело результат собственного {@code UPDATE}, а не состояние до него.
     */
    @Override
    public @NotNull CompletableFuture<ConsumeOutcome> consumeByHash(@NotNull String tokenHash,
                                                                     @NotNull TokenType expectedType,
                                                                     @NotNull Instant at,
                                                                     @Nullable IpAddress ip) {
        return sql.transaction(connection -> {
            try (java.sql.PreparedStatement update = connection.prepareStatement("""
                    UPDATE tokens SET used = 1, used_at = ?, used_ip = ?
                    WHERE token_hash = ? AND type = ? AND used = 0 AND revoked = 0
                      AND expires_at > ?
                    """)) {
                update.setObject(1, SqlTypes.toTimestamp(at));
                update.setObject(2, SqlTypes.ipToBytes(ip));
                update.setString(3, tokenHash);
                update.setString(4, expectedType.name());
                update.setObject(5, SqlTypes.toTimestamp(at));

                boolean won = update.executeUpdate() > 0;
                Optional<AuthToken> row = readByHash(connection, tokenHash);

                if (won) {
                    return new ConsumeOutcome(ConsumeStatus.CONSUMED, row.orElse(null));
                }
                return new ConsumeOutcome(explain(row, expectedType, at), row.orElse(null));
            }
        });
    }

    /** Читает строку токена тем же соединением, что выполняло погашение. */
    private static Optional<AuthToken> readByHash(java.sql.Connection connection, String tokenHash)
            throws SQLException {
        try (java.sql.PreparedStatement select = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM tokens WHERE token_hash = ?")) {
            select.setString(1, tokenHash);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Почему {@code UPDATE} не изменил ни одной строки.
     *
     * <p>Порядок проверок повторяет порядок условий запроса, поэтому объяснение
     * не может разойтись с решением.
     */
    private static ConsumeStatus explain(Optional<AuthToken> row, TokenType expectedType, Instant at) {
        if (row.isEmpty()) {
            return ConsumeStatus.NOT_FOUND;
        }
        AuthToken token = row.get();
        if (token.type() != expectedType) {
            return ConsumeStatus.WRONG_TYPE;
        }
        if (token.revoked()) {
            return ConsumeStatus.REVOKED;
        }
        if (token.isExpired(at)) {
            return ConsumeStatus.EXPIRED;
        }
        return ConsumeStatus.ALREADY_USED;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> revoke(long tokenId, @NotNull Instant at) {
        return sql.update("UPDATE tokens SET revoked = 1, revoked_at = ? WHERE id = ? AND revoked = 0",
                SqlTypes.toTimestamp(at), tokenId
        ).thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<Integer> revokeChain(long tokenId, @NotNull Instant at) {
        // Обход цепочки в обе стороны: к корню по parent_token_id и от него ко всем
        // потомкам. Рекурсивный CTE был бы короче, но обход в Java не зависит от
        // версии MySQL и легче читается при разборе инцидента.
        return sql.withConnectionAsync(connection -> {
            Set<Long> chain = new HashSet<>();

            // Вверх до корня.
            Long current = tokenId;
            while (current != null && chain.add(current)) {
                try (var statement = connection.prepareStatement(
                        "SELECT parent_token_id FROM tokens WHERE id = ?")) {
                    statement.setLong(1, current);
                    try (ResultSet rs = statement.executeQuery()) {
                        current = rs.next() ? SqlTypes.readNullableLong(rs, "parent_token_id") : null;
                    }
                }
            }

            // Вниз по всем потомкам.
            Deque<Long> queue = new ArrayDeque<>(chain);
            while (!queue.isEmpty()) {
                Long parent = queue.poll();
                try (var statement = connection.prepareStatement(
                        "SELECT id FROM tokens WHERE parent_token_id = ?")) {
                    statement.setLong(1, parent);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            long child = rs.getLong("id");
                            if (chain.add(child)) {
                                queue.add(child);
                            }
                        }
                    }
                }
            }

            int revoked = 0;
            try (var statement = connection.prepareStatement(
                    "UPDATE tokens SET revoked = 1, revoked_at = ? WHERE id = ? AND revoked = 0")) {
                for (Long id : chain) {
                    statement.setTimestamp(1, SqlTypes.toTimestamp(at));
                    statement.setLong(2, id);
                    revoked += statement.executeUpdate();
                }
            }
            return revoked;
        });
    }

    @Override
    public @NotNull CompletableFuture<Integer> revokeAllByAccountAndType(long accountId,
                                                                         @NotNull TokenType type,
                                                                         @NotNull Instant at) {
        return sql.update("""
                        UPDATE tokens SET revoked = 1, revoked_at = ?
                        WHERE account_id = ? AND type = ? AND revoked = 0
                        """,
                SqlTypes.toTimestamp(at), accountId, type.name());
    }

    @Override
    public @NotNull CompletableFuture<List<AuthToken>> findUsableByAccountAndType(long accountId,
                                                                                   @NotNull TokenType type,
                                                                                   @NotNull Instant at) {
        return sql.queryList("""
                        SELECT %s FROM tokens
                        WHERE account_id = ? AND type = ? AND revoked = 0 AND used = 0
                          AND expires_at > ?
                        ORDER BY issued_at DESC
                        """.formatted(COLUMNS),
                JdbcTokenRepository::map,
                accountId, type.name(), SqlTypes.toTimestamp(at));
    }

    @Override
    public @NotNull CompletableFuture<Integer> countUsable(long accountId,
                                                           @NotNull TokenType type,
                                                           @NotNull Instant at) {
        return sql.queryInt("""
                        SELECT COUNT(*) FROM tokens
                        WHERE account_id = ? AND type = ? AND revoked = 0 AND used = 0
                          AND expires_at > ?
                        """,
                0, accountId, type.name(), SqlTypes.toTimestamp(at));
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteExpiredAndUsed(@NotNull Instant before) {
        // Удаляются только отработавшие токены. Действующие не трогаем, даже если
        // они старые: API-ключ живёт год и не должен исчезнуть при чистке.
        return sql.update("""
                        DELETE FROM tokens
                        WHERE expires_at < ? AND (used = 1 OR revoked = 1 OR expires_at < ?)
                        """,
                SqlTypes.toTimestamp(before), SqlTypes.toTimestamp(before));
    }

    /** Список идентификаторов цепочки — для диагностики инцидентов. */
    public @NotNull CompletableFuture<List<Long>> chainIds(long tokenId) {
        return sql.queryList(
                "SELECT id FROM tokens WHERE id = ? OR parent_token_id = ?",
                rs -> rs.getLong("id"),
                tokenId, tokenId).thenApply(ArrayList::new);
    }
}
