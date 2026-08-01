package net.kofnetwork.auth.discord;

import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.kofnetwork.auth.core.security.TokenGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Кнопки подтверждения входа.
 *
 * <p>Проверяется то, что ломается не при сборке, а при первом реальном нажатии:
 * длина {@code custom_id} и разбор токена обратно из него.
 */
class ApprovalButtonsTest {

    /** Ограничение Discord на {@code custom_id}. */
    private static final int CUSTOM_ID_LIMIT = 100;

    /** Код подтверждения такой же длины, какую выдаёт TokenService. */
    private static String approvalCode() {
        return TokenGenerator.humanReadableCode(10);
    }

    @Test
    void идентификатор_кнопки_помещается_в_ограничение_discord() {
        // Регрессия: раньше LOGIN_APPROVAL выпускался как 64 знака hex. Вместе
        // с префиксом это ещё влезало сюда, но не влезало в 64 байта Telegram,
        // и один и тот же токен нельзя было использовать в обоих ботах.
        List<Button> buttons = KoFAuthDiscordBot.approvalButtons(approvalCode());

        assertThat(buttons).allSatisfy(button ->
                assertThat(button.getId().getBytes(StandardCharsets.UTF_8).length)
                        .isLessThanOrEqualTo(CUSTOM_ID_LIMIT));
    }

    @Test
    void кнопок_ровно_две_и_они_различаются_смыслом() {
        List<Button> buttons = KoFAuthDiscordBot.approvalButtons(approvalCode());

        assertThat(buttons).hasSize(2);
        // Отказ обязан быть визуально отличим: перепутанные кнопки означают
        // подтверждённый вход там, где владелец хотел его отклонить.
        assertThat(buttons.get(0).getStyle()).isEqualTo(ButtonStyle.SUCCESS);
        assertThat(buttons.get(1).getStyle()).isEqualTo(ButtonStyle.DANGER);
        assertThat(buttons.get(0).getId()).isNotEqualTo(buttons.get(1).getId());
    }

    @Test
    void токен_восстанавливается_из_идентификатора_без_потерь() {
        // Обработчик нажатия отрезает префикс и предъявляет остаток как токен.
        // Расхождение здесь означало бы, что подтверждение не срабатывает никогда.
        String token = approvalCode();
        List<Button> buttons = KoFAuthDiscordBot.approvalButtons(token);

        for (Button button : buttons) {
            String id = button.getId();
            String extracted = id.substring(id.lastIndexOf(':') + 1);
            assertThat(extracted).isEqualTo(token);
        }
    }

    @Test
    void подтверждение_и_отказ_различимы_по_префиксу() {
        String token = approvalCode();
        List<Button> buttons = KoFAuthDiscordBot.approvalButtons(token);

        assertThat(buttons.get(0).getId()).startsWith("kofauth:approve:");
        assertThat(buttons.get(1).getId()).startsWith("kofauth:deny:");
    }
}
