package net.kofnetwork.auth.paper.captcha;

import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.CaptchaType;
import net.kofnetwork.auth.api.service.CaptchaService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Раскладка CAPTCHA для GUI Paper.
 *
 * <p>Реализует точку расширения {@link CaptchaService.CaptchaRenderer}: сервис в Core
 * не знает ни о Bukkit, ни о том, как выглядит сетка, — он лишь просит разложить
 * задачу и получает от игрока ответ.
 *
 * <p>Поддерживает три типа, использующих инвентарь. {@link CaptchaType#TEXT_INPUT}
 * обслуживает {@link ChatCaptchaRenderer}.
 */
public final class GuiCaptchaRenderer implements CaptchaService.CaptchaRenderer {

    /**
     * Маркер в заголовке окна.
     *
     * <p>По нему {@code LimboProtectionListener} отличает окно капчи от любого
     * другого инвентаря: в капче кликать нужно, во всём остальном — нельзя.
     * Символ нулевой ширины невидим в интерфейсе и не появится в чужом окне случайно.
     */
    private static final String MARKER = "​";

    /** Предметы для сетки. Различимы с первого взгляда и есть во всех версиях 1.21. */
    private static final List<String> ITEMS = List.of(
            "DIAMOND", "EMERALD", "GOLD_INGOT", "IRON_INGOT", "REDSTONE",
            "LAPIS_LAZULI", "COAL", "QUARTZ", "AMETHYST_SHARD", "COPPER_INGOT");

    private final String title;
    private final int cells;

    /**
     * @param title заголовок окна из конфигурации
     * @param cells число ячеек сетки
     */
    public GuiCaptchaRenderer(@NotNull String title, int cells) {
        this.title = MARKER + title;
        // Инвентарь сундука обязан быть кратен девяти и не длиннее 54 ячеек:
        // Bukkit бросает исключение на любом другом размере.
        int requested = Math.max(9, Math.min(cells, 54));
        int roundedUp = requested % 9 == 0 ? requested : ((requested / 9) + 1) * 9;
        this.cells = Math.min(roundedUp, 54);
    }

    @Override
    public @NotNull List<CaptchaType> supportedTypes() {
        return List.of(CaptchaType.GUI_GRID, CaptchaType.BLOCK_SELECT, CaptchaType.BUTTON_CLICK);
    }

    /**
     * Раскладывает сетку так, чтобы названный предмет оказался ровно в той ячейке,
     * номер которой является правильным ответом.
     *
     * @param answer номер ячейки, считая с единицы
     */
    @Override
    public @NotNull CaptchaService.RenderedCaptcha render(@NotNull CaptchaChallenge challenge,
                                                          @NotNull String answer) {
        int targetIndex = parseIndex(answer);
        String target = ITEMS.get(ThreadLocalRandom.current().nextInt(ITEMS.size()));

        // Отвлекающие варианты — любые, кроме искомого: иначе в сетке окажется
        // несколько «правильных» предметов, и задача перестанет иметь один ответ.
        List<String> distractors = new ArrayList<>(ITEMS);
        distractors.remove(target);
        Collections.shuffle(distractors);

        List<String> options = new ArrayList<>(cells);
        for (int i = 0; i < cells; i++) {
            options.add(distractors.get(i % distractors.size()));
        }
        options.set(targetIndex, target);

        return new CaptchaService.RenderedCaptcha(
                "Нажмите: " + humanize(target), options, null);
    }

    /**
     * Разбирает номер ячейки.
     *
     * <p>Значение приходит из сервиса и по построению лежит в диапазоне, но
     * ограничение здесь всё равно есть: выход за границы списка означал бы
     * {@code IndexOutOfBoundsException} при показе задачи игроку.
     */
    private int parseIndex(String answer) {
        try {
            int oneBased = Integer.parseInt(answer.trim());
            return Math.max(0, Math.min(oneBased - 1, cells - 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Заголовок окна. */
    public @NotNull String title() {
        return title;
    }

    /** Число ячеек сетки, кратное девяти. */
    public int cells() {
        return cells;
    }

    /** Является ли инвентарь окном капчи. */
    public static boolean isCaptchaInventory(@NotNull String inventoryTitle) {
        return inventoryTitle.startsWith(MARKER);
    }

    /** Человекочитаемое название предмета. */
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
            default -> material.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }
}
