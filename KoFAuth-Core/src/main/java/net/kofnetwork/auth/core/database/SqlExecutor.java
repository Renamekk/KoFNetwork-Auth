package net.kofnetwork.auth.core.database;

import net.kofnetwork.auth.api.exception.RepositoryException;
import net.kofnetwork.auth.core.concurrent.AsyncExecutors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Выполнение SQL поверх {@link DatabaseManager}.
 *
 * <p>Снимает с репозиториев работу с {@code try-with-resources}, преобразованием
 * {@link SQLException} и переключением на пул базы. Репозиторий пишет запрос и
 * отображение строки — всё остальное здесь.
 *
 * <p><b>Только подготовленные выражения.</b> Все методы принимают SQL с
 * placeholder'ами и отдельный массив параметров. Метода, принимающего готовую
 * строку с подставленными значениями, в этом классе нет — и это единственная
 * работающая защита от SQL-инъекций: не «не забывать экранировать», а не иметь
 * возможности сконкатенировать запрос.
 */
public final class SqlExecutor {

    private final DatabaseManager database;
    private final AsyncExecutors executors;

    public SqlExecutor(@NotNull DatabaseManager database, @NotNull AsyncExecutors executors) {
        this.database = database;
        this.executors = executors;
    }

    /** Отображение строки результата в объект. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(@NotNull ResultSet resultSet) throws SQLException;
    }

    /** Действие в рамках одного соединения — для транзакций и многошаговых операций. */
    @FunctionalInterface
    public interface ConnectionCallback<T> {
        T execute(@NotNull Connection connection) throws SQLException;
    }

    // ------------------------------------------------------------------ выборка

    /** Одна строка или пустой {@link Optional}. */
    public <T> @NotNull CompletableFuture<Optional<T>> queryOne(@NotNull String sql,
                                                                @NotNull RowMapper<T> mapper,
                                                                @Nullable Object... params) {
        return executors.supplyDatabase(() -> withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.<T>empty();
                }
            }
        }, sql));
    }

    /** Список строк. */
    public <T> @NotNull CompletableFuture<List<T>> queryList(@NotNull String sql,
                                                              @NotNull RowMapper<T> mapper,
                                                              @Nullable Object... params) {
        return executors.supplyDatabase(() -> withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                try (ResultSet rs = statement.executeQuery()) {
                    List<T> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(mapper.map(rs));
                    }
                    return result;
                }
            }
        }, sql));
    }

    /** Скалярное значение первой колонки первой строки. */
    public @NotNull CompletableFuture<Long> queryLong(@NotNull String sql,
                                                       long defaultValue,
                                                       @Nullable Object... params) {
        return queryOne(sql, rs -> rs.getLong(1), params).thenApply(value -> value.orElse(defaultValue));
    }

    /** Число строк, удовлетворяющих условию. */
    public @NotNull CompletableFuture<Integer> queryInt(@NotNull String sql,
                                                         int defaultValue,
                                                         @Nullable Object... params) {
        return queryOne(sql, rs -> rs.getInt(1), params).thenApply(value -> value.orElse(defaultValue));
    }

    // ------------------------------------------------------------------ изменение

    /**
     * {@code INSERT}, {@code UPDATE} или {@code DELETE}.
     *
     * @return число затронутых строк
     */
    public @NotNull CompletableFuture<Integer> update(@NotNull String sql, @Nullable Object... params) {
        return executors.supplyDatabase(() -> withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                return statement.executeUpdate();
            }
        }, sql));
    }

    /**
     * {@code INSERT} с возвратом сгенерированного ключа.
     *
     * @return значение автоинкремента
     * @throws RepositoryException если ключ не был возвращён
     */
    public @NotNull CompletableFuture<Long> insertReturningKey(@NotNull String sql,
                                                                @Nullable Object... params) {
        return executors.supplyDatabase(() -> withConnection(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, params);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                    throw new SQLException("Вставка не вернула сгенерированный ключ");
                }
            }
        }, sql));
    }

    /**
     * Пакетная вставка.
     *
     * <p>Ради неё в строке подключения включён {@code rewriteBatchedStatements}:
     * без него драйвер MySQL отправляет каждую строку отдельным запросом, и
     * «пакет» экономит только вызовы JDBC, но не сетевые обходы.
     *
     * @param batch список наборов параметров
     * @return суммарное число затронутых строк
     */
    public @NotNull CompletableFuture<Integer> batch(@NotNull String sql,
                                                      @NotNull List<Object[]> batch) {
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return executors.supplyDatabase(() -> withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (Object[] params : batch) {
                    bind(statement, params);
                    statement.addBatch();
                }
                int total = 0;
                for (int affected : statement.executeBatch()) {
                    // Statement.SUCCESS_NO_INFO (-2) означает успех без счётчика.
                    total += affected > 0 ? affected : 0;
                }
                return total;
            }
        }, sql));
    }

    // ------------------------------------------------------------------ транзакции

    /**
     * Выполняет действие в транзакции.
     *
     * <p>При исключении выполняется откат, при успехе — фиксация. Исходное значение
     * {@code autoCommit} восстанавливается в любом случае: соединение возвращается
     * в пул и будет выдано другому запросу, который вправе ожидать значение по умолчанию.
     */
    public <T> @NotNull CompletableFuture<T> transaction(@NotNull ConnectionCallback<T> callback) {
        return executors.supplyDatabase(() -> {
            try (Connection connection = database.connection()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = callback.execute(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    throw translate(e, "транзакция");
                } finally {
                    connection.setAutoCommit(previousAutoCommit);
                }
            } catch (SQLException e) {
                throw translate(e, "транзакция");
            }
        });
    }

    /** Выполняет действие на одном соединении без транзакции. */
    public <T> @NotNull CompletableFuture<T> withConnectionAsync(@NotNull ConnectionCallback<T> callback) {
        return executors.supplyDatabase(() -> withConnection(callback, "составная операция"));
    }

    // ------------------------------------------------------------------ внутреннее

    private <T> T withConnection(ConnectionCallback<T> callback, String sql) {
        try (Connection connection = database.connection()) {
            return callback.execute(connection);
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    /**
     * Привязывает параметры.
     *
     * <p>{@code setObject} корректно обрабатывает {@code null}, примитивы, {@code byte[]}
     * для {@code VARBINARY} и {@link java.sql.Timestamp}. Преобразованием
     * {@link java.time.Instant} в {@code Timestamp} занимается вызывающий репозиторий:
     * делать это здесь означало бы прятать выбор часового пояса от того, кто пишет запрос.
     */
    private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    /**
     * Превращает {@link SQLException} в {@link RepositoryException}, сохраняя признак
     * нарушения уникальности.
     *
     * <p>Различать этот случай обязательно: гонка двух регистраций одного ника —
     * не отказ базы, а штатный исход, который сервис обязан показать игроку как
     * «ник занят», а не как «внутренняя ошибка».
     */
    private static RepositoryException translate(Exception e, String context) {
        if (isConstraintViolation(e)) {
            return new DuplicateKeyException("Нарушено ограничение уникальности: " + e.getMessage(), e);
        }
        return new RepositoryException("Ошибка при выполнении SQL (" + shorten(context) + ")", e);
    }

    private static boolean isConstraintViolation(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (current instanceof SQLException sql && "23000".equals(sql.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String shorten(String sql) {
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }

    /** Нарушение уникального ключа — отдельный тип, чтобы сервис мог его распознать. */
    public static final class DuplicateKeyException extends RepositoryException {

        private static final long serialVersionUID = 1L;

        public DuplicateKeyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
