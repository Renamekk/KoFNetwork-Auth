package net.kofnetwork.auth.core.security;

import org.apache.commons.codec.binary.Hex;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Генерация одноразовых кодов и токенов, а также их хэширование для хранения.
 *
 * <p>Единственный источник случайности — {@link SecureRandom}. {@code java.util.Random}
 * и {@code Math.random()} предсказуемы: увидев несколько выданных значений, можно
 * вычислить состояние генератора и предсказать следующий код восстановления пароля.
 */
public final class TokenGenerator {

    /**
     * Алфавит кодов, вводимых человеком: 32 знака.
     *
     * <p>Исключены четыре символа — {@code 0}, {@code O}, {@code 1}, {@code I}: игрок
     * читает код с экрана телефона и вводит в чат, и именно эти пары путают чаще всего.
     * {@code L} оставлен: спутать его можно только со строчной {@code l}, а алфавит
     * целиком в верхнем регистре.
     *
     * <p>Потеря четырёх знаков стоит около 0,2 бита на символ — приемлемая цена за то,
     * что поддержка не разбирает жалобы «код не подходит».
     */
    private static final char[] HUMAN_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenGenerator() {
        throw new AssertionError("Утилитный класс не подлежит созданию");
    }

    /**
     * Токен для машинного использования: refresh, ссылка подтверждения почты.
     *
     * @param bytes длина энтропии; 32 байта = 256 бит, перебор невозможен
     * @return значение в hex
     */
    public static @NotNull String randomToken(int bytes) {
        if (bytes < 16) {
            throw new IllegalArgumentException(
                    "Токен короче 16 байт (128 бит) небезопасен, запрошено " + bytes);
        }
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Hex.encodeHexString(raw);
    }

    /** Токен длиной 32 байта — значение по умолчанию для refresh и ссылок. */
    public static @NotNull String randomToken() {
        return randomToken(32);
    }

    /**
     * Код, который игрок вводит вручную: привязка Telegram, подтверждение почты.
     *
     * <p>Короткие коды защищены не длиной, а сроком жизни и ограничением попыток:
     * 8 знаков этого алфавита дают около 40 бит, чего достаточно при трёх попытках
     * за пятнадцать минут.
     *
     * @param length число знаков, не меньше 6
     */
    public static @NotNull String humanReadableCode(int length) {
        if (length < 6) {
            throw new IllegalArgumentException(
                    "Код короче 6 знаков перебирается за разумное время, запрошено " + length);
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(HUMAN_ALPHABET[RANDOM.nextInt(HUMAN_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** Числовой код подтверждения — привычный формат для писем. */
    public static @NotNull String numericCode(int digits) {
        if (digits < 4) {
            throw new IllegalArgumentException("Числовой код короче 4 цифр небезопасен");
        }
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            sb.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return sb.toString();
    }

    /**
     * Резервный код TOTP в читаемом виде {@code XXXX-XXXX-XXXX}.
     *
     * <p>Разделители нужны, потому что этот код игрок переписывает на бумагу и вводит
     * в стрессовой ситуации — когда потерял телефон.
     */
    public static @NotNull String recoveryCode() {
        String raw = humanReadableCode(12);
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12);
    }

    /**
     * SHA-256 от значения токена в hex — то, что уходит в колонку {@code tokens.token_hash}.
     *
     * <p>Почему не BCrypt, как для паролей: пароль выбирает человек, у него низкая
     * энтропия, и он нуждается в замедленном хэше. Токен состоит из 256 случайных бит, перебирать его
     * бессмысленно при любой скорости хэширования, а BCrypt в этом месте лишь замедлил бы
     * каждую проверку сессии на сотню миллисекунд.
     */
    public static @NotNull String hash(@NotNull String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Hex.encodeHexString(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязателен для любой реализации Java.
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    /**
     * Сравнение в постоянном времени.
     *
     * <p>Обычное {@code equals} для строк выходит на первом несовпавшем символе, и по
     * времени ответа можно посимвольно подобрать значение. Для хэшей токенов и ответов
     * CAPTCHA это применимо, поэтому сравнение идёт через
     * {@link MessageDigest#isEqual(byte[], byte[])}.
     */
    public static boolean constantTimeEquals(@NotNull String a, @NotNull String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Одноразовый идентификатор для запросов подтверждения входа.
     * Живёт в Redis и гасится атомарно, поэтому 16 байт достаточно.
     */
    public static @NotNull String nonce() {
        return randomToken(16);
    }
}
