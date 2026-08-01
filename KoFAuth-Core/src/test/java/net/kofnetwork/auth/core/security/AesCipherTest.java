package net.kofnetwork.auth.core.security;

import net.kofnetwork.auth.api.exception.ConfigurationException;
import net.kofnetwork.auth.api.exception.KoFAuthException;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesCipherTest {

    private final AesCipher cipher = AesCipher.fromBase64(AesCipher.generateKey());

    @Test
    void шифрует_и_расшифровывает_строку() {
        String secret = "JBSWY3DPEHPK3PXP";

        byte[] encrypted = cipher.encrypt(secret);

        assertThat(cipher.decryptToString(encrypted)).isEqualTo(secret);
    }

    @Test
    void шифротекст_не_содержит_открытого_текста() {
        byte[] encrypted = cipher.encrypt("СЕКРЕТНОЕ_ЗНАЧЕНИЕ");

        assertThat(new String(encrypted, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("СЕКРЕТНОЕ_ЗНАЧЕНИЕ");
    }

    @Test
    void одно_значение_шифруется_каждый_раз_по_разному() {
        // Ключевое свойство GCM: IV случаен на каждую операцию. Повторное
        // использование пары «ключ + IV» раскрывает открытый текст.
        Set<String> ciphertexts = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            ciphertexts.add(Base64.getEncoder().encodeToString(cipher.encrypt("одно и то же")));
        }

        assertThat(ciphertexts).hasSize(20);
    }

    @Test
    void обнаруживает_подмену_шифротекста() {
        // Ради этого выбран GCM, а не CBC: подмена должна быть замечена.
        byte[] encrypted = cipher.encrypt("важное значение");
        encrypted[encrypted.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decryptToString(encrypted))
                .isInstanceOf(KoFAuthException.class)
                .hasMessageContaining("повреждены");
    }

    @Test
    void обнаруживает_подмену_IV() {
        byte[] encrypted = cipher.encrypt("важное значение");
        encrypted[0] ^= 0x01;

        assertThatThrownBy(() -> cipher.decryptToString(encrypted))
                .isInstanceOf(KoFAuthException.class);
    }

    @Test
    void чужой_ключ_не_расшифровывает() {
        AesCipher other = AesCipher.fromBase64(AesCipher.generateKey());
        byte[] encrypted = cipher.encrypt("значение");

        assertThatThrownBy(() -> other.decryptToString(encrypted))
                .isInstanceOf(KoFAuthException.class);
    }

    @Test
    void отвергает_слишком_короткое_значение() {
        assertThatThrownBy(() -> cipher.decryptToString(new byte[]{1, 2, 3}))
                .isInstanceOf(KoFAuthException.class)
                .hasMessageContaining("повреждена");
    }

    @Test
    void decryptToStringOrNull_возвращает_null_вместо_исключения() {
        byte[] broken = cipher.encrypt("значение");
        broken[broken.length - 1] ^= 0x01;

        assertThat(cipher.decryptToStringOrNull(broken)).isNull();
        assertThat(cipher.decryptToStringOrNull(null)).isNull();
        assertThat(cipher.decryptToStringOrNull(cipher.encrypt("ок"))).isEqualTo("ок");
    }

    @Test
    void генерирует_ключ_нужной_длины() {
        byte[] raw = Base64.getDecoder().decode(AesCipher.generateKey());

        assertThat(raw).hasSize(AesCipher.KEY_LENGTH_BYTES);
    }

    @Test
    void генерирует_каждый_раз_новый_ключ() {
        assertThat(AesCipher.generateKey()).isNotEqualTo(AesCipher.generateKey());
    }

    @Test
    void отвергает_пустой_ключ_с_подсказкой() {
        assertThatThrownBy(() -> AesCipher.fromBase64(""))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("KOFAUTH_SECURITY_ENCRYPTION_KEY");
    }

    @Test
    void отвергает_ключ_неверной_длины() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> AesCipher.fromBase64(shortKey))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("32");
    }

    @Test
    void отвергает_некорректный_Base64() {
        assertThatThrownBy(() -> AesCipher.fromBase64("это не base64 !!!"))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void переносит_пустую_строку_и_юникод() {
        assertThat(cipher.decryptToString(cipher.encrypt(""))).isEmpty();
        assertThat(cipher.decryptToString(cipher.encrypt("Привет, 世界! 🎮")))
                .isEqualTo("Привет, 世界! 🎮");
    }
}
