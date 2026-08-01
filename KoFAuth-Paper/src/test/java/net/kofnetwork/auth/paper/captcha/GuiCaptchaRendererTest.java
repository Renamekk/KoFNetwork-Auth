package net.kofnetwork.auth.paper.captcha;

import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.CaptchaType;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.service.CaptchaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class GuiCaptchaRendererTest {

    private final GuiCaptchaRenderer renderer =
            new GuiCaptchaRenderer("<dark_gray>Проверка", 27);

    private static CaptchaChallenge challenge() {
        return CaptchaChallenge.issue(1L, UUID.randomUUID(), CaptchaType.GUI_GRID,
                "a".repeat(64), IpAddress.of("203.0.113.7"), 3, Duration.ofMinutes(2));
    }

    /** Название предмета, указанного в подсказке. */
    private static String promptTarget(CaptchaService.RenderedCaptcha rendered) {
        return rendered.prompt().substring(rendered.prompt().indexOf(':') + 1).trim();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "5", "14", "27"})
    void искомый_предмет_лежит_ровно_в_ячейке_ответа(String answer) {
        // Главное свойство: без него задача не имеет решения — рендерер разложил
        // бы отвлекающие варианты во все ячейки, включая правильную.
        CaptchaService.RenderedCaptcha rendered = renderer.render(challenge(), answer);

        int expectedIndex = Integer.parseInt(answer) - 1;
        String target = rendered.options().get(expectedIndex);

        assertThat(promptTarget(rendered)).isEqualTo(humanize(target));
    }

    @Test
    void искомый_предмет_встречается_в_сетке_ровно_один_раз() {
        // Иначе у задачи было бы несколько «правильных» ячеек, а верной
        // считается только одна — игрок проиграл бы, кликнув на верный предмет.
        CaptchaService.RenderedCaptcha rendered = renderer.render(challenge(), "10");
        String target = rendered.options().get(9);

        long occurrences = rendered.options().stream().filter(target::equals).count();

        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    void сетка_заполнена_целиком() {
        CaptchaService.RenderedCaptcha rendered = renderer.render(challenge(), "1");

        assertThat(rendered.options()).hasSize(renderer.cells());
        assertThat(rendered.options()).doesNotContainNull();
    }

    @Test
    void размер_сетки_округляется_вверх_до_кратного_девяти() {
        // Bukkit бросает исключение на инвентаре некратного размера.
        assertThat(new GuiCaptchaRenderer("t", 12).cells()).isEqualTo(18);
        assertThat(new GuiCaptchaRenderer("t", 27).cells()).isEqualTo(27);
        assertThat(new GuiCaptchaRenderer("t", 1).cells()).isEqualTo(9);
        assertThat(new GuiCaptchaRenderer("t", 100).cells()).isEqualTo(54);
    }

    @Test
    void некорректный_ответ_не_роняет_раскладку() {
        // Значение приходит из сервиса и по построению корректно, но выход за
        // границы означал бы исключение прямо при показе задачи игроку.
        assertThat(renderer.render(challenge(), "0").options()).hasSize(27);
        assertThat(renderer.render(challenge(), "999").options()).hasSize(27);
        assertThat(renderer.render(challenge(), "не число").options()).hasSize(27);
    }

    @Test
    void заголовок_помечен_невидимым_маркером() {
        // По нему слушатель защиты отличает окно капчи от любого другого
        // инвентаря: там кликать нужно, во всём остальном — нельзя.
        assertThat(GuiCaptchaRenderer.isCaptchaInventory(renderer.title())).isTrue();
        assertThat(GuiCaptchaRenderer.isCaptchaInventory("Сундук")).isFalse();
        assertThat(GuiCaptchaRenderer.isCaptchaInventory("")).isFalse();
    }

    @Test
    void раскладка_различается_между_выдачами() {
        // Одинаковая сетка позволила бы запомнить ячейку и проходить проверку
        // не глядя.
        long distinct = IntStream.range(0, 30)
                .mapToObj(i -> renderer.render(challenge(), "5").options().get(4))
                .distinct()
                .count();

        assertThat(distinct).isGreaterThan(1);
    }

    @Test
    void поддерживает_три_типа_использующих_инвентарь() {
        assertThat(renderer.supportedTypes()).containsExactlyInAnyOrder(
                CaptchaType.GUI_GRID, CaptchaType.BLOCK_SELECT, CaptchaType.BUTTON_CLICK);
    }

    /** Повторяет отображение названий из рендерера. */
    private static String humanize(String material) {
        return switch (material) {
            case "DIAMOND" -> "алмаз";
            case "EMERALD" -> "изумруд";
            case "GOLD_INGOT" -> "золотой слиток";
            case "IRON_INGOT" -> "железный слиток";
            case "REDSTONE" -> "редстоун";
            case "LAPIS_LAZULI" -> "лазурит";
            case "COAL" -> "уголь";
            case "QUARTZ" -> "кварц";
            case "AMETHYST_SHARD" -> "аметист";
            case "COPPER_INGOT" -> "медный слиток";
            default -> material.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }
}
