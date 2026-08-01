package net.kofnetwork.auth.core.database;

import net.kofnetwork.auth.api.model.IpAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Преобразования между типами Java и колонками MySQL.
 *
 * <p>Собрано в одном месте, потому что каждое из этих преобразований —
 * потенциальная ошибка, которую легко повторить в четырнадцати репозиториях
 * по-разному. Особенно это касается времени: смешать {@code Instant} в UTC с
 * {@code Timestamp} в локальном поясе можно незаметно, а обнаружится это через
 * полгода при разборе инцидента.
 */
public final class SqlTypes {

    private SqlTypes() {
        throw new AssertionError("Утилитный класс не подлежит созданию");
    }

    // ------------------------------------------------------------------ UUID <-> BINARY(16)

    /** UUID в 16 байт для колонки {@code BINARY(16)}. */
    public static byte @NotNull [] uuidToBytes(@NotNull UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /** UUID из 16 байт. */
    public static @NotNull UUID uuidFromBytes(byte @NotNull [] bytes) {
        if (bytes.length != 16) {
            throw new IllegalArgumentException("UUID должен занимать 16 байт, получено " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /** UUID из колонки. {@code null}, если значение отсутствует. */
    public static @Nullable UUID readUuid(@NotNull ResultSet rs, @NotNull String column) throws SQLException {
        byte[] bytes = rs.getBytes(column);
        return bytes == null ? null : uuidFromBytes(bytes);
    }

    /** UUID из колонки, которая по схеме не может быть {@code NULL}. */
    public static @NotNull UUID readRequiredUuid(@NotNull ResultSet rs, @NotNull String column)
            throws SQLException {
        UUID value = readUuid(rs, column);
        if (value == null) {
            throw new SQLException("Колонка " + column + " не может быть NULL");
        }
        return value;
    }

    // ------------------------------------------------------------------ IpAddress <-> VARBINARY(16)

    /** Адрес в байты для колонки {@code VARBINARY(16)}. */
    public static byte @Nullable [] ipToBytes(@Nullable IpAddress ip) {
        return ip == null ? null : ip.toBytes();
    }

    /**
     * Адрес из колонки.
     *
     * <p>Повреждённое значение (неверная длина) возвращается как
     * {@link IpAddress#UNKNOWN}, а не бросает исключение: одна испорченная строка в
     * истории входов не должна ломать выборку целиком.
     */
    public static @Nullable IpAddress readIp(@NotNull ResultSet rs, @NotNull String column)
            throws SQLException {
        byte[] bytes = rs.getBytes(column);
        if (bytes == null) {
            return null;
        }
        try {
            return IpAddress.ofBytes(bytes);
        } catch (IllegalArgumentException e) {
            return IpAddress.UNKNOWN;
        }
    }

    /** Адрес из колонки, которая по схеме не может быть {@code NULL}. */
    public static @NotNull IpAddress readRequiredIp(@NotNull ResultSet rs, @NotNull String column)
            throws SQLException {
        IpAddress value = readIp(rs, column);
        return value == null ? IpAddress.UNKNOWN : value;
    }

    // ------------------------------------------------------------------ Instant <-> DATETIME(3)

    /**
     * Момент времени в {@link Timestamp} для колонки {@code DATETIME(3)}.
     *
     * <p>Пояс не применяется: в строке подключения задано {@code connectionTimeZone=UTC}
     * и {@code preserveInstants=true}, поэтому драйвер записывает ровно то же
     * мгновение, что и передано.
     *
     * <p><b>Значение обрезается до миллисекунд.</b> {@code DATETIME(3)} хранит три знака
     * после запятой, и MySQL при вставке <em>округляет</em>, а не отбрасывает лишнее:
     * {@code 10:00:00.753838900} сохраняется как {@code 10:00:00.754}. Прочитанный
     * обратно момент оказывается на доли миллисекунды <em>больше</em> записанного, и
     * инварианты вида «срок сессии не превышает потолок» начинают нарушаться на
     * ровном месте. Обрезая здесь, мы гарантируем, что записанное и прочитанное
     * совпадают побитово.
     */
    public static @Nullable Timestamp toTimestamp(@Nullable Instant instant) {
        return instant == null
                ? null
                : Timestamp.from(instant.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    /** Момент времени из колонки. */
    public static @Nullable Instant readInstant(@NotNull ResultSet rs, @NotNull String column)
            throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** Момент времени из колонки, которая по схеме не может быть {@code NULL}. */
    public static @NotNull Instant readRequiredInstant(@NotNull ResultSet rs, @NotNull String column)
            throws SQLException {
        Instant value = readInstant(rs, column);
        if (value == null) {
            throw new SQLException("Колонка " + column + " не может быть NULL");
        }
        return value;
    }

    // ------------------------------------------------------------------ перечисления

    /**
     * Значение перечисления из колонки.
     *
     * <p>Неизвестное значение возвращает {@code fallback}, а не бросает исключение:
     * база могла пережить откат версии приложения, в которой было больше вариантов,
     * и падать на выборке из-за этого нельзя.
     */
    public static <E extends Enum<E>> @NotNull E readEnum(@NotNull ResultSet rs,
                                                          @NotNull String column,
                                                          @NotNull Class<E> type,
                                                          @NotNull E fallback) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Значение перечисления из колонки, допускающей {@code NULL}. */
    public static <E extends Enum<E>> @Nullable E readNullableEnum(@NotNull ResultSet rs,
                                                                   @NotNull String column,
                                                                   @NotNull Class<E> type)
            throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ SET

    /**
     * Множество значений в строку для колонки типа {@code SET}.
     *
     * <p>MySQL хранит {@code SET} как битовую маску, а по протоколу передаёт
     * значения через запятую без пробелов. Пробел после запятой MySQL воспримет
     * как часть названия варианта и отвергнет вставку.
     */
    public static @NotNull String enumSetToString(@Nullable Collection<? extends Enum<?>> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>(values.size());
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return String.join(",", names);
    }

    /** Множество значений из колонки типа {@code SET}. */
    public static <E extends Enum<E>> @NotNull Set<E> readEnumSet(@NotNull ResultSet rs,
                                                                   @NotNull String column,
                                                                   @NotNull Class<E> type)
            throws SQLException {
        String raw = rs.getString(column);
        EnumSet<E> result = EnumSet.noneOf(type);
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(Enum.valueOf(type, trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                // Неизвестный вариант из более новой версии схемы — пропускаем.
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ прочее

    /** {@code Long} из колонки, допускающей {@code NULL}. */
    public static @Nullable Long readNullableLong(@NotNull ResultSet rs, @NotNull String column)
            throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** {@code Integer} из колонки, допускающей {@code NULL}. */
    public static @Nullable Integer readNullableInt(@NotNull ResultSet rs, @NotNull String column)
            throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** Логическое значение из колонки {@code TINYINT(1)}. */
    public static boolean readBoolean(@NotNull ResultSet rs, @NotNull String column) throws SQLException {
        return rs.getBoolean(column);
    }
}
