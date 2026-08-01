package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/**
 * IP-адрес в том виде, в каком он хранится в базе: 4 байта для IPv4, 16 для IPv6.
 *
 * <p>Зачем отдельный тип вместо {@link String}:
 * <ul>
 *   <li>колонка в MySQL — {@code VARBINARY(16)}, и конверсия должна быть в одном месте,
 *       а не размазана по десятку репозиториев;</li>
 *   <li>строковое представление одного адреса неоднозначно ({@code ::1} и
 *       {@code 0:0:0:0:0:0:0:1} — один адрес, но разные строки), поэтому сравнение
 *       и агрегация по IP на строках даёт ложные несовпадения;</li>
 *   <li>тип запрещает перепутать IP с ником в сигнатуре метода.</li>
 * </ul>
 *
 * <p>Класс неизменяемый; массив байтов копируется на входе и на выходе.
 */
public final class IpAddress {

    /** Заглушка для случаев, когда адрес недоступен (консоль, системное действие). */
    public static final IpAddress UNKNOWN = new IpAddress(new byte[]{0, 0, 0, 0});

    private final byte[] bytes;

    private IpAddress(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * Разбирает текстовое представление адреса.
     *
     * <p>Намеренно используется {@link InetAddress#getByName(String)} только для литералов:
     * если строка не является IP-литералом, JDK попытается выполнить DNS-запрос, что в
     * горячем пути входа недопустимо. Поэтому вход сначала проверяется на наличие
     * разделителей, а имена хостов отвергаются.
     *
     * @param value адрес, например {@code 192.168.0.1} или {@code 2001:db8::1}
     * @throws IllegalArgumentException если строка не является IP-литералом
     */
    public static @NotNull IpAddress of(@NotNull String value) {
        Objects.requireNonNull(value, "value");
        String trimmed = stripZoneAndPort(value.trim());
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Пустая строка не является IP-адресом");
        }
        if (!isIpLiteral(trimmed)) {
            throw new IllegalArgumentException("Не IP-литерал: " + value);
        }
        try {
            return new IpAddress(InetAddress.getByName(trimmed).getAddress());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Некорректный IP-адрес: " + value, e);
        }
    }

    /**
     * Разбирает адрес, возвращая {@link #UNKNOWN} вместо исключения.
     * Применяется там, где источник данных не контролируется (заголовок HTTP, старая запись в БД).
     */
    public static @NotNull IpAddress ofNullable(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return of(value);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public static @NotNull IpAddress of(@NotNull InetAddress address) {
        return new IpAddress(address.getAddress().clone());
    }

    /**
     * Восстанавливает адрес из байтов колонки {@code VARBINARY(16)}.
     *
     * @throws IllegalArgumentException если длина не 4 и не 16 байт
     */
    public static @NotNull IpAddress ofBytes(byte @NotNull [] raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.length != 4 && raw.length != 16) {
            throw new IllegalArgumentException("Длина IP должна быть 4 или 16 байт, получено " + raw.length);
        }
        return new IpAddress(raw.clone());
    }

    /** Байты для записи в {@code VARBINARY(16)}. Возвращается копия. */
    public byte @NotNull [] toBytes() {
        return bytes.clone();
    }

    public boolean isIpv4() {
        return bytes.length == 4;
    }

    public boolean isUnknown() {
        return this.equals(UNKNOWN);
    }

    /**
     * Локальный или приватный адрес. Используется AntiVPN и rate-limit'ом: для трафика
     * из локальной сети (например, самого прокси) внешние проверки бессмысленны.
     */
    public boolean isLoopbackOrPrivate() {
        try {
            InetAddress address = InetAddress.getByAddress(bytes);
            return address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            // getByAddress бросает только при неверной длине, которая проверена в конструкторе.
            return false;
        }
    }

    /**
     * Префикс адреса для группировки в AntiBot: /24 для IPv4, /64 для IPv6.
     *
     * <p>Атакующий с ботнетом обычно распоряжается подсетью, а не одиночным адресом,
     * поэтому лимиты имеет смысл считать и на уровне подсети.
     */
    public @NotNull IpAddress subnet() {
        byte[] masked = bytes.clone();
        int keep = isIpv4() ? 3 : 8;
        Arrays.fill(masked, keep, masked.length, (byte) 0);
        return new IpAddress(masked);
    }

    /** Каноничное текстовое представление. */
    public @NotNull String asString() {
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            return Arrays.toString(bytes);
        }
    }

    /**
     * Представление для показа игроку и записи в открытые логи: последний октет IPv4
     * и последние 64 бита IPv6 скрыты.
     *
     * <p>Полный адрес виден только владельцу аккаунта и администраторам с правом
     * {@code kofauth.admin.logs}; в общий чат и в уведомления уходит именно эта форма.
     */
    public @NotNull String asMasked() {
        String full = asString();
        if (isIpv4()) {
            int lastDot = full.lastIndexOf('.');
            return lastDot < 0 ? full : full.substring(0, lastDot) + ".***";
        }
        return subnet().asString().replace("0:0:0:0", "****");
    }

    /** Отбрасывает зону IPv6 ({@code %eth0}) и порт ({@code 1.2.3.4:25565}, {@code [::1]:25565}). */
    private static String stripZoneAndPort(String value) {
        String result = value;
        if (result.startsWith("[")) {
            int close = result.indexOf(']');
            if (close > 0) {
                result = result.substring(1, close);
            }
        } else {
            int firstColon = result.indexOf(':');
            // Один двоеточие — это IPv4 с портом. Несколько — это IPv6 без порта.
            if (firstColon > 0 && result.indexOf(':', firstColon + 1) < 0) {
                result = result.substring(0, firstColon);
            }
        }
        int percent = result.indexOf('%');
        return percent > 0 ? result.substring(0, percent) : result;
    }

    /**
     * Проверяет, что строка — IP-литерал, а не имя хоста. Защищает от неявного DNS-запроса
     * внутри {@link InetAddress#getByName(String)}.
     */
    private static boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            // IPv6: только hex-цифры, двоеточия и точки (для отображения IPv4-mapped).
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean allowed = c == ':' || c == '.'
                        || (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if (!allowed) {
                    return false;
                }
            }
            return true;
        }
        // IPv4: ровно четыре десятичных октета.
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * Преобразует к {@link InetAddress}. Нужно интеграциям, работающим с сокетами напрямую.
     */
    public @NotNull InetAddress toInetAddress() {
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Некорректная длина адреса", e);
        }
    }

    /** {@code true}, если адрес относится к семейству IPv4. */
    public boolean isIpv4Family() {
        return isIpv4() || toInetAddress() instanceof Inet4Address;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof IpAddress other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /**
     * Возвращает <em>маскированный</em> адрес. Это сделано намеренно: {@code toString()}
     * попадает в логи через строковую интерполяцию чаще всего случайно, и полный IP
     * не должен утекать в общий лог по недосмотру. Полное значение — {@link #asString()}.
     */
    @Override
    public String toString() {
        return asMasked();
    }
}
