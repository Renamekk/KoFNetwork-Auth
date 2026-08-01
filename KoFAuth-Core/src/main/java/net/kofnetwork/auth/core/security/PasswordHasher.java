package net.kofnetwork.auth.core.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import net.kofnetwork.auth.api.exception.ConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Хэширование и проверка паролей на BCrypt.
 *
 * <p><b>О стоимости.</b> Параметр cost задаёт {@code 2^cost} итераций. Значение 12 —
 * примерно 100–250 мс на современном процессоре. Это осознанный компромисс: слишком
 * низкое значение делает перебор украденной базы дешёвым, слишком высокое превращает
 * вход в отказ в обслуживании — при 40 логинах в секунду и 400 мс на проверку
 * потребовалось бы 16 полностью занятых ядер только на BCrypt.
 *
 * <p><b>Об ограничении длины.</b> BCrypt использует лишь первые 72 байта пароля.
 * Молча обрезать длинный пароль нельзя: пользователь будет уверен, что его
 * 100-символьная парольная фраза защищает его целиком, тогда как реально работает
 * только начало. Поэтому длинные пароли отвергаются {@link PasswordPolicy} до
 * попадания сюда, а здесь стоит проверка-страховка.
 */
public final class PasswordHasher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordHasher.class);

    /** Предел BCrypt: байты сверх этого числа алгоритм не учитывает. */
    public static final int MAX_PASSWORD_BYTES = 72;

    /** Ниже этого значения хэш считается небезопасным и не принимается. */
    public static final int MIN_COST = 10;

    /** Выше этого значения проверка становится слишком дорогой для игрового сервера. */
    public static final int MAX_COST = 16;

    /** Идентификатор алгоритма, попадающий в {@code users.password_algorithm}. */
    public static final String ALGORITHM = "BCRYPT";

    /** Заведомо не совпадающий с {@link #timingEqualizerHash} пароль для {@link #wasteTime()}. */
    private static final char[] TIMING_PROBE = "kofauth-timing-probe".toCharArray();

    private final int cost;

    /**
     * Хэш той же стоимости, что и рабочая, вычисленный один раз при создании.
     * Используется только в {@link #wasteTime()}.
     */
    private final char[] timingEqualizerHash;

    /**
     * @param cost стоимость BCrypt, 10..16
     * @throws ConfigurationException если значение вне допустимого диапазона
     */
    public PasswordHasher(int cost) {
        if (cost < MIN_COST || cost > MAX_COST) {
            throw new ConfigurationException(
                    "Стоимость BCrypt должна быть в диапазоне " + MIN_COST + ".." + MAX_COST
                            + ", получено " + cost + ". Значение ниже " + MIN_COST
                            + " делает перебор украденной базы дешёвым.");
        }
        this.cost = cost;
        // Один раз при старте: в горячем пути должна остаться ровно одна операция verify.
        this.timingEqualizerHash = BCrypt.withDefaults()
                .hashToChar(cost, "kofauth-timing-equalizer".toCharArray());
    }

    /**
     * Хэширует пароль.
     *
     * @throws IllegalArgumentException если пароль длиннее {@value #MAX_PASSWORD_BYTES} байт
     */
    public @NotNull String hash(@NotNull String password) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException(
                    "Пароль длиннее " + MAX_PASSWORD_BYTES + " байт (" + bytes + "): "
                            + "BCrypt учитывает только начало, и хэширование целиком создало бы "
                            + "ложное ощущение защищённости");
        }
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    /**
     * Проверяет пароль против хэша.
     *
     * <p>Никогда не бросает исключение на повреждённом хэше — возвращает {@code false}.
     * Испорченная строка в базе не должна валить обработку входа: игрок просто не
     * сможет войти, а администратор увидит запись в логе.
     */
    public boolean verify(@NotNull String password, @NotNull String hash) {
        try {
            return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
        } catch (RuntimeException e) {
            LOGGER.error("Не удалось проверить пароль: хэш в базе повреждён или имеет неизвестный формат", e);
            return false;
        }
    }

    /**
     * Нужно ли перехэшировать пароль под текущую стоимость.
     *
     * <p>Позволяет поднимать cost по мере роста мощности процессоров, не заставляя
     * игроков менять пароли: при следующем успешном входе пароль известен в открытом
     * виде, и его можно перехэшировать бесплатно.
     *
     * @return {@code true}, если хэш выпущен с меньшей стоимостью, чем настроена сейчас
     */
    public boolean needsRehash(@NotNull String hash) {
        int existing = extractCost(hash);
        return existing > 0 && existing < cost;
    }

    /**
     * Достаёт стоимость из строки хэша формата {@code $2a$12$...}.
     *
     * @return стоимость либо {@code -1}, если строка не разбирается
     */
    static int extractCost(@NotNull String hash) {
        // Формат: $<версия>$<cost>$<соль+хэш>. Ищем число между вторым и третьим '$'.
        if (hash.length() < 7 || hash.charAt(0) != '$') {
            return -1;
        }
        int second = hash.indexOf('$', 1);
        if (second < 0) {
            return -1;
        }
        int third = hash.indexOf('$', second + 1);
        if (third < 0 || third - second != 3) {
            return -1;
        }
        try {
            return Integer.parseInt(hash.substring(second + 1, third));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Настроенная стоимость. */
    public int cost() {
        return cost;
    }

    /**
     * Тратит столько же времени, сколько заняла бы настоящая проверка, но ничего не проверяет.
     *
     * <p>Вызывается, когда аккаунта не существует. Без этого несуществующий ник отвечает
     * за миллисекунду, а существующий — за сотню, и разница в задержке выдаёт наличие
     * аккаунта даже при одинаковом тексте ошибки.
     *
     * <p>Выполняется ровно одна операция {@code verify} против заранее вычисленного
     * хэша той же стоимости — столько же работы, сколько в настоящей проверке.
     * Вычислять хэш здесь же было бы ошибкой: хэширование и проверка вместе стоят
     * вдвое дороже реального пути, и несуществующий ник стал бы отвечать заметно
     * <em>медленнее</em> существующего, то есть утечка по времени осталась бы,
     * только с обратным знаком.
     */
    public void wasteTime() {
        BCrypt.verifyer().verify(TIMING_PROBE, timingEqualizerHash);
    }
}
