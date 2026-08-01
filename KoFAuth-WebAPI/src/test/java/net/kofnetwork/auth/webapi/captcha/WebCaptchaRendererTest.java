package net.kofnetwork.auth.webapi.captcha;

import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.CaptchaType;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.service.CaptchaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class WebCaptchaRendererTest {

    private final WebCaptchaRenderer renderer = new WebCaptchaRenderer(6);

    private static CaptchaChallenge challenge(CaptchaType type) {
        return CaptchaChallenge.issue(1L, UUID.randomUUID(), type,
                "a".repeat(64), IpAddress.of("203.0.113.7"), 3, Duration.ofMinutes(2));
    }

    /** Название, указанное в задании. */
    private static String promptTarget(CaptchaService.RenderedCaptcha rendered) {
        return rendered.prompt().substring(rendered.prompt().indexOf(':') + 1).trim();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "3", "6"})
    void искомый_вариант_лежит_ровно_в_ячейке_ответа(String answer) {
        // Без этого задача не имеет решения: рендерер разложил бы отвлекающие
        // варианты по всем ячейкам, включая правильную.
        var rendered = renderer.render(challenge(CaptchaType.BUTTON_CLICK), answer);

        int expected = Integer.parseInt(answer) - 1;
        assertThat(rendered.options().get(expected)).isEqualTo(promptTarget(rendered));
    }

    @Test
    void искомый_вариант_встречается_ровно_один_раз() {
        var rendered = renderer.render(challenge(CaptchaType.BUTTON_CLICK), "2");

        long occurrences = rendered.options().stream()
                .filter(option -> option.equals(promptTarget(rendered)))
                .count();

        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    void число_вариантов_равно_настроенному() {
        var rendered = renderer.render(challenge(CaptchaType.BUTTON_CLICK), "1");

        assertThat(rendered.options()).hasSize(6);
    }

    @Test
    void меньше_четырёх_вариантов_не_бывает() {
        // Три варианта угадываются в трети случаев — это не проверка.
        assertThat(new WebCaptchaRenderer(2).buttons()).isEqualTo(4);
    }

    @Test
    void номер_вне_диапазона_не_роняет_раскладку() {
        // Значение приходит из сервиса и по построению в диапазоне, но выход
        // за границы означал бы IndexOutOfBounds при показе задачи.
        assertThat(renderer.render(challenge(CaptchaType.BUTTON_CLICK), "99").options())
                .hasSize(6);
        assertThat(renderer.render(challenge(CaptchaType.BUTTON_CLICK), "не число").options())
                .hasSize(6);
    }

    @Test
    void текстовая_задача_отдаётся_картинкой() throws Exception {
        var rendered = renderer.render(challenge(CaptchaType.TEXT_INPUT), "K7M4QP");

        assertThat(rendered.options()).isEmpty();
        assertThat(rendered.imageBase64()).isNotNull();

        byte[] png = Base64.getDecoder().decode(rendered.imageBase64());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

        // Читается как PNG — значит клиент действительно сможет его показать.
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isPositive();
        assertThat(image.getHeight()).isPositive();
    }

    @Test
    void ответ_не_попадает_в_текст_текстовой_задачи() {
        // Ради этого код и растрируется: строкой в JSON он был бы отдан боту
        // напрямую, и задача перестала бы что-либо проверять.
        String answer = "K7M4QP";
        var rendered = renderer.render(challenge(CaptchaType.TEXT_INPUT), answer);

        assertThat(rendered.prompt()).doesNotContain(answer);
        assertThat(rendered.options()).isEmpty();
    }

    @Test
    void картинка_различается_для_одинакового_кода() {
        // Помехи случайны: одинаковый байт-в-байт ответ позволил бы боту
        // сопоставлять картинку с уже разгаданной по хэшу.
        String answer = "K7M4QP";
        var first = renderer.render(challenge(CaptchaType.TEXT_INPUT), answer);
        var second = renderer.render(challenge(CaptchaType.TEXT_INPUT), answer);

        assertThat(first.imageBase64()).isNotEqualTo(second.imageBase64());
    }

    @Test
    void все_объявленные_типы_действительно_раскладываются() {
        // Сервис выбирает рендерер по supportedTypes: тип из списка, который
        // он не умеет разложить, обернулся бы отказом уже в бою.
        for (CaptchaType type : renderer.supportedTypes()) {
            assertThat(renderer.render(challenge(type), "1")).isNotNull();
        }
    }

    @Test
    void раскладка_не_повторяется_из_раза_в_раз() {
        // Постоянная раскладка означала бы, что бот один раз находит верную
        // ячейку и дальше нажимает её всегда.
        var prompts = IntStream.range(0, 30)
                .mapToObj(i -> promptTarget(renderer.render(challenge(CaptchaType.BUTTON_CLICK), "1")))
                .distinct()
                .count();

        assertThat(prompts).isGreaterThan(1);
    }
}
