package net.kofnetwork.auth.core.security;

import net.kofnetwork.auth.api.exception.ConfigurationException;
import net.kofnetwork.auth.api.exception.KoFAuthException;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Симметричное шифрование секретов: AES-256 в режиме GCM.
 *
 * <p>Применяется к тому, что нужно уметь прочитать обратно: секреты TOTP,
 * OAuth-токены Discord. Пароли этим классом <b>не</b> шифруются — они хэшируются
 * (см. {@link PasswordHasher}), потому что восстанавливать их незачем, а
 * обратимое шифрование пароля означает, что владелец ключа знает все пароли.
 *
 * <p><b>Почему GCM, а не CBC.</b> GCM даёт аутентификацию: подмена или порча
 * шифротекста обнаруживается при расшифровке. CBC без отдельного HMAC позволяет
 * атакующему с доступом к базе изменять содержимое, а сам шифр этого не заметит.
 *
 * <p><b>Формат записи:</b> {@code IV (12 байт) ‖ шифротекст ‖ тег (16 байт)}.
 * IV хранится рядом со значением, потому что он не секретен — он обязан быть
 * уникальным. IV генерируется случайно на каждую операцию шифрования: повторное
 * использование пары «ключ + IV» в GCM катастрофично, оно раскрывает открытый
 * текст и позволяет подделывать сообщения.
 */
public final class AesCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";

    /** Длина IV для GCM. 12 байт — размер, для которого GCM определён без доп. преобразования. */
    private static final int IV_LENGTH = 12;

    /** Длина тега аутентификации в битах. */
    private static final int TAG_LENGTH_BITS = 128;

    /** Требуемая длина ключа: AES-256. */
    public static final int KEY_LENGTH_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    private AesCipher(SecretKey key) {
        this.key = key;
    }

    /**
     * Создаёт шифр из ключа в Base64.
     *
     * @param base64Key ключ длиной 32 байта, закодированный Base64
     * @throws ConfigurationException если ключ отсутствует, не разбирается или не той длины
     */
    public static @NotNull AesCipher fromBase64(@NotNull String base64Key) {
        if (base64Key.isBlank()) {
            throw new ConfigurationException(
                    "Ключ шифрования не задан. Сгенерируйте его через AesCipher.generateKey() "
                            + "и укажите в security.yml либо в переменной KOFAUTH_SECURITY_ENCRYPTION_KEY.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Ключ шифрования не является корректным Base64", e);
        }
        if (raw.length != KEY_LENGTH_BYTES) {
            // Затираем: некорректный ключ всё равно не должен задерживаться в памяти.
            Arrays.fill(raw, (byte) 0);
            throw new ConfigurationException(
                    "Ключ шифрования должен быть длиной " + KEY_LENGTH_BYTES + " байт (AES-256), "
                            + "получено " + raw.length);
        }
        SecretKey secretKey = new SecretKeySpec(raw, KEY_ALGORITHM);
        Arrays.fill(raw, (byte) 0);
        return new AesCipher(secretKey);
    }

    /**
     * Генерирует новый ключ в Base64.
     *
     * <p>Вызывается администратором один раз при развёртывании. Смена ключа делает
     * нечитаемыми все ранее зашифрованные значения — секреты TOTP придётся
     * перевыпустить, поэтому ключ следует хранить вместе с резервной копией базы.
     */
    public static @NotNull String generateKey() {
        byte[] raw = new byte[KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(raw);
        String encoded = Base64.getEncoder().encodeToString(raw);
        Arrays.fill(raw, (byte) 0);
        return encoded;
    }

    /**
     * Шифрует строку.
     *
     * @return {@code IV ‖ шифротекст ‖ тег} — готово для колонки {@code VARBINARY}
     */
    public byte @NotNull [] encrypt(@NotNull String plaintext) {
        return encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /** Шифрует произвольные байты. */
    public byte @NotNull [] encrypt(byte @NotNull [] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new KoFAuthException("Не удалось зашифровать значение", e);
        }
    }

    /**
     * Расшифровывает строку.
     *
     * @throws KoFAuthException если данные повреждены, подменены либо зашифрованы
     *                          другим ключом — GCM не различает эти случаи, и это
     *                          правильно: любой из них означает, что доверять
     *                          значению нельзя
     */
    public @NotNull String decryptToString(byte @NotNull [] payload) {
        return new String(decrypt(payload), StandardCharsets.UTF_8);
    }

    /** Расшифровывает в байты. */
    public byte @NotNull [] decrypt(byte @NotNull [] payload) {
        if (payload.length <= IV_LENGTH) {
            throw new KoFAuthException(
                    "Зашифрованное значение короче минимально возможного (" + payload.length
                            + " байт): запись повреждена");
        }
        try {
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new KoFAuthException(
                    "Не удалось расшифровать значение: данные повреждены, подменены "
                            + "или зашифрованы другим ключом", e);
        }
    }

    /**
     * Расшифровывает, возвращая {@code null} вместо исключения.
     *
     * <p>Для случаев, когда одна испорченная запись не должна ломать выборку:
     * например, при выгрузке списка привязок Discord.
     */
    public String decryptToStringOrNull(byte[] payload) {
        if (payload == null) {
            return null;
        }
        try {
            return decryptToString(payload);
        } catch (KoFAuthException e) {
            return null;
        }
    }
}
