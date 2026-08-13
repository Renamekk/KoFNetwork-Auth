package net.kofnetwork.auth.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Builds the animated KoF Network title without moving its endpoint colours.
 *
 * <p>MiniMessage's gradient phase is cyclic: as soon as the phase is non-zero,
 * the colour after the last stop wraps back to the first one. That is useful for
 * rainbow text, but it makes the final {@code F} cease to be gold and the final
 * letter of {@code Network} cease to be white. Here the animation changes the
 * curve of a monotonic interpolation instead. Its endpoints therefore remain
 * exact for every frame.</p>
 */
final class BrandGradient {

    static final TextColor RED = TextColor.color(0xF40D01);
    static final TextColor GOLD = TextColor.color(0xFFD700);
    static final TextColor WHITE = TextColor.color(0xFFFFFF);

    private static final double CURVE_AMPLITUDE = 1.2;

    private BrandGradient() {
    }

    static Component kof(double angle) {
        return word("KoF", RED, GOLD, angle);
    }

    static Component network(double angle) {
        return word("Network", GOLD, WHITE, angle);
    }

    private static Component word(String word, TextColor from, TextColor to, double angle) {
        int[] codePoints = word.codePoints().toArray();
        Component result = Component.empty();
        for (int index = 0; index < codePoints.length; index++) {
            double linear = codePoints.length == 1 ? 0.0 : (double) index / (codePoints.length - 1);
            float progress = (float) progress(linear, angle);
            Component letter = Component.text(new String(Character.toChars(codePoints[index])))
                    .color(TextColor.lerp(progress, from, to))
                    .decorate(TextDecoration.BOLD);
            result = result.append(letter);
        }
        return result;
    }

    /** A monotonic animated curve with immutable 0 and 1 endpoints. */
    static double progress(double linear, double angle) {
        if (linear <= 0.0) {
            return 0.0;
        }
        if (linear >= 1.0) {
            return 1.0;
        }
        double curve = Math.sin(angle) * CURVE_AMPLITUDE;
        if (Math.abs(curve) < 1.0e-9) {
            return linear;
        }
        return Math.expm1(curve * linear) / Math.expm1(curve);
    }
}
