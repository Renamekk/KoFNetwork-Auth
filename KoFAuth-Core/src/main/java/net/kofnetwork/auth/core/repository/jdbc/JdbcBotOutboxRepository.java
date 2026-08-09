package net.kofnetwork.auth.core.repository.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kofnetwork.auth.api.model.BotMessage;
import net.kofnetwork.auth.api.model.BotPlatform;
import net.kofnetwork.auth.api.repository.BotOutboxRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link BotOutboxRepository} на JDBC. */
public final class JdbcBotOutboxRepository implements BotOutboxRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcBotOutboxRepository.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String COLUMNS = """
            id, platform, recipient_id, chat_id, kind, payload, created_at, expires_at
            """;

    private final SqlExecutor sql;

    public JdbcBotOutboxRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    static @NotNull BotMessage map(@NotNull ResultSet rs) throws SQLException {
        return new BotMessage(
                rs.getLong("id"),
                SqlTypes.readEnum(rs, "platform", BotPlatform.class, BotPlatform.TELEGRAM),
                rs.getLong("recipient_id"),
                rs.getLong("chat_id"),
                SqlTypes.readEnum(rs, "kind", BotMessage.Kind.class, BotMessage.Kind.LOGIN_NOTICE),
                readPayload(rs.getString("payload")),
                SqlTypes.readRequiredInstant(rs, "created_at"),
                SqlTypes.readRequiredInstant(rs, "expires_at"));
    }

    private static Map<String, String> readPayload(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            // Повреждённая строка не должна ронять выборку целиком: бот получит
            // сообщение без полей и просто ничего по нему не покажет.
            LOGGER.warn("Не удалось разобрать payload сообщения бота: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String writePayload(Map<String, String> payload) {
        try {
            return JSON.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            LOGGER.warn("Не удалось сериализовать payload сообщения бота: {}", e.getMessage());
            return "{}";
        }
    }

    @Override
    public @NotNull CompletableFuture<BotMessage> append(@NotNull BotMessage message) {
        return sql.insertReturningKey("""
                        INSERT INTO bot_outbox (platform, recipient_id, chat_id, kind, payload,
                                                created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                message.platform().name(),
                message.recipientId(),
                message.chatId(),
                message.kind().name(),
                writePayload(message.payload()),
                SqlTypes.toTimestamp(message.createdAt()),
                SqlTypes.toTimestamp(message.expiresAt())
        ).thenApply(message::withId);
    }

    @Override
    public @NotNull CompletableFuture<List<BotMessage>> readAfter(@NotNull BotPlatform platform,
                                                                   long after,
                                                                   int limit,
                                                                   @NotNull Instant now) {
        // Просроченные пропускаются прямо в запросе: показывать кнопку входа,
        // срок которой истёк, значит обещать то, чего уже нельзя сделать.
        return sql.queryList("""
                        SELECT %s FROM bot_outbox
                         WHERE platform = ? AND id > ? AND expires_at > ?
                         ORDER BY id
                         LIMIT %d
                        """.formatted(COLUMNS, Math.max(1, Math.min(500, limit))),
                JdbcBotOutboxRepository::map,
                platform.name(), after, SqlTypes.toTimestamp(now));
    }

    @Override
    public @NotNull CompletableFuture<Long> cursor(@NotNull BotPlatform platform) {
        return sql.queryOne("SELECT position FROM bot_outbox_cursor WHERE platform = ?",
                        rs -> rs.getLong("position"), platform.name())
                .thenApply(found -> found.orElse(0L));
    }

    /**
     * Курсор двигается только вперёд.
     *
     * <p>{@code GREATEST} в запросе, а не сравнение в Java: два экземпляра WebAPI могут
     * обслуживать одного бота, и запоздавшее подтверждение с меньшим значением иначе
     * откатило бы курсор — сеть заново разослала бы уже обработанные сообщения.
     */
    @Override
    public @NotNull CompletableFuture<Long> acknowledge(@NotNull BotPlatform platform, long cursor) {
        return sql.update("""
                        INSERT INTO bot_outbox_cursor (platform, position, updated_at)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            position = GREATEST(position, VALUES(position)),
                            updated_at = VALUES(updated_at)
                        """,
                platform.name(), Math.max(0L, cursor), SqlTypes.toTimestamp(Instant.now())
        ).thenCompose(ignored -> cursor(platform));
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteExpired(@NotNull Instant before) {
        return sql.update("DELETE FROM bot_outbox WHERE expires_at < ?",
                SqlTypes.toTimestamp(before));
    }
}
