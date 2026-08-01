package net.kofnetwork.auth.telegram;

import net.kofnetwork.auth.core.security.TokenGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Длина данных инлайн-кнопки подтверждения входа.
 *
 * <p>Тест существует из-за конкретной поломки: {@code LOGIN_APPROVAL} выпускался
 * как {@code randomToken()} — 32 байта в hex, то есть 64 знака. Вместе с префиксом
 * {@code approve:} получалось 72 байта при жёстком пределе Telegram в 64, и
 * {@code sendMessage} отвергался бы уже на стороне API.
 *
 * <p>Ни компилятор, ни сборка этого не показывали: код кнопок существовал, но
 * не вызывался ниоткуда, поэтому предел ни разу не проверялся живым запросом.
 */
class ApprovalCallbackDataTest {

    /** Жёсткое ограничение Telegram Bot API на {@code callback_data}. */
    private static final int CALLBACK_DATA_LIMIT = 64;

    /** Самый длинный из префиксов, которые бот добавляет к токену. */
    private static final String LONGEST_PREFIX = "approve:";

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 10, 16, 32})
    void код_подтверждения_с_префиксом_умещается_в_callback_data(int length) {
        String code = TokenGenerator.humanReadableCode(length);

        assertThat(bytes(LONGEST_PREFIX + code)).isLessThanOrEqualTo(CALLBACK_DATA_LIMIT);
    }

    @Test
    void код_по_умолчанию_умещается_с_запасом() {
        // Длина по умолчанию — 10 знаков (security.yml, two-factor.approval-code-length).
        String code = TokenGenerator.humanReadableCode(10);

        assertThat(bytes(LONGEST_PREFIX + code)).isEqualTo(18);
    }

    @Test
    void прежний_формат_токена_в_callback_data_не_поместился_бы() {
        // Фиксируем причину замены: если кто-то вернёт randomToken() для
        // LOGIN_APPROVAL, этот тест объяснит, почему так делать нельзя.
        String oldStyle = TokenGenerator.randomToken();

        assertThat(oldStyle).hasSize(64);
        assertThat(bytes(LONGEST_PREFIX + oldStyle)).isGreaterThan(CALLBACK_DATA_LIMIT);
    }

    @Test
    void код_состоит_только_из_печатных_ascii() {
        // Telegram считает предел в байтах, а не в символах: кириллица или
        // эмодзи в коде съели бы лимит вдвое-вчетверо быстрее.
        String code = TokenGenerator.humanReadableCode(10);

        assertThat(bytes(code)).isEqualTo(code.length());
        assertThat(code).matches("[A-Z0-9]+");
    }
}
