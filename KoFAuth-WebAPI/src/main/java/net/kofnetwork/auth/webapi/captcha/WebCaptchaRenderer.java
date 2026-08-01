package net.kofnetwork.auth.webapi.captcha;

import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.CaptchaType;
import net.kofnetwork.auth.api.service.CaptchaService;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Раскладка CAPTCHA для браузера.
 *
 * <p>Реализует ту же точку расширения {@link CaptchaService.CaptchaRenderer}, что и
 * рендереры Paper, — сервис в Core не знает, что задача уедет в JSON.
 *
 * <p><b>Почему текстовая задача рисуется картинкой.</b> В Minecraft код можно
 * показать строкой в чате: там он хотя бы проходит через игровой клиент. В HTTP-ответе
 * строка с ответом — это ответ, отданный боту напрямую, и задача перестаёт что-либо
 * проверять. Поэтому код растрируется в PNG с наклоном символов и помехами, а в
 * {@code prompt} уходит только формулировка задания.
 *
 * <p>Искажения намеренно умеренные: цель — сломать наивный OCR, а не сделать код
 * нечитаемым для человека. Против промышленного распознавания это всё равно не
 * защита, поэтому в связке работают ограничение скорости и AntiBot.
 */
public final class WebCaptchaRenderer implements CaptchaService.CaptchaRenderer {

    /** Варианты для задачи с кнопками. Различимы на слух и на вид, не путаются между собой. */
    private static final List<String> LABELS = List.of(
            "алмаз", "изумруд", "золото", "железо", "редстоун",
            "лазурит", "уголь", "кварц", "аметист", "медь");

    private static final int IMAGE_WIDTH = 220;
    private static final int IMAGE_HEIGHT = 70;

    /** Число вариантов в задаче с кнопками. */
    private final int buttons;

    public WebCaptchaRenderer(int buttons) {
        // Меньше четырёх вариантов — задача угадывается с вероятностью выше 25%.
        // Больше десяти — не из чего набрать несовпадающие подписи.
        this.buttons = Math.max(4, Math.min(buttons, LABELS.size()));
    }

    @Override
    public @NotNull List<CaptchaType> supportedTypes() {
        return List.of(CaptchaType.TEXT_INPUT, CaptchaType.MAP_IMAGE, CaptchaType.BUTTON_CLICK);
    }

    @Override
    public @NotNull CaptchaService.RenderedCaptcha render(@NotNull CaptchaChallenge challenge,
                                                          @NotNull String answer) {
        return switch (challenge.type()) {
            case BUTTON_CLICK, GUI_GRID, BLOCK_SELECT -> renderButtons(answer);
            case TEXT_INPUT, MAP_IMAGE -> renderImage(answer);
        };
    }

    /**
     * Задача с кнопками: подписи уезжают клиенту, номер верной — нет.
     *
     * @param answer номер варианта, считая с единицы
     */
    private CaptchaService.RenderedCaptcha renderButtons(String answer) {
        int targetIndex = parseIndex(answer);
        String target = LABELS.get(ThreadLocalRandom.current().nextInt(LABELS.size()));

        // Отвлекающие подписи — любые, кроме искомой: иначе верных вариантов
        // окажется несколько и у задачи не будет единственного ответа.
        List<String> distractors = new ArrayList<>(LABELS);
        distractors.remove(target);
        Collections.shuffle(distractors);

        List<String> options = new ArrayList<>(buttons);
        for (int i = 0; i < buttons; i++) {
            options.add(distractors.get(i % distractors.size()));
        }
        options.set(targetIndex, target);

        return new CaptchaService.RenderedCaptcha("Выберите: " + target, options, null);
    }

    /** Растрирует код в PNG и отдаёт его base64. */
    private CaptchaService.RenderedCaptcha renderImage(String answer) {
        return new CaptchaService.RenderedCaptcha(
                "Введите код с картинки", List.of(), Base64.getEncoder().encodeToString(draw(answer)));
    }

    /**
     * Рисует код.
     *
     * <p>{@code TYPE_INT_RGB}, а не {@code ARGB}: прозрачность здесь не нужна, а
     * PNG без альфа-канала примерно на треть меньше — картинка уходит в каждом
     * ответе на выдачу задачи.
     */
    private static byte[] draw(String code) {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            ThreadLocalRandom random = ThreadLocalRandom.current();

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x1E1E24));
            g.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

            // Помехи под текстом: линии, пересекающие символы, мешают сегментации —
            // именно на ней спотыкается наивный распознаватель.
            g.setStroke(new BasicStroke(1.4f));
            for (int i = 0; i < 7; i++) {
                g.setColor(new Color(random.nextInt(0x50, 0x90),
                        random.nextInt(0x50, 0x90), random.nextInt(0x60, 0xA0)));
                g.drawLine(random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT),
                        random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT));
            }

            int step = code.isEmpty() ? IMAGE_WIDTH : (IMAGE_WIDTH - 30) / code.length();
            for (int i = 0; i < code.length(); i++) {
                AffineTransform saved = g.getTransform();

                int x = 18 + i * step;
                int y = 46 + random.nextInt(-6, 7);
                g.rotate(random.nextDouble(-0.35, 0.35), x, y);

                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, random.nextInt(30, 39)));
                g.setColor(new Color(random.nextInt(0xC0, 0x100),
                        random.nextInt(0xC0, 0x100), random.nextInt(0xC0, 0x100)));
                g.drawString(String.valueOf(code.charAt(i)), x, y);

                g.setTransform(saved);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            // ImageIO объявляет IOException, но пишет в массив в памяти:
            // отказать здесь может только нехватка памяти, и это не наш случай.
            throw new UncheckedIOException("Не удалось нарисовать CAPTCHA", e);
        } finally {
            g.dispose();
        }
    }

    /** Число вариантов в задаче с кнопками. */
    public int buttons() {
        return buttons;
    }

    /**
     * Разбирает номер варианта.
     *
     * <p>Значение приходит из сервиса и по построению лежит в диапазоне, но выход
     * за границы списка означал бы {@code IndexOutOfBoundsException} при показе.
     */
    private int parseIndex(String answer) {
        try {
            int oneBased = Integer.parseInt(answer.trim());
            return Math.max(0, Math.min(oneBased - 1, buttons - 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
