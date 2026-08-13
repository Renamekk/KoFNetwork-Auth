package net.kofnetwork.auth.paper;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrandGradientTest {

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.7, 1.5707963267948966, 3.141592653589793,
            4.71238898038469, 5.8})
    void крайние_цвета_бренда_не_двигаются_при_анимации(double angle) {
        List<Component> kof = BrandGradient.kof(angle).children();
        List<Component> network = BrandGradient.network(angle).children();

        assertThat(kof).hasSize(3);
        assertThat(kof.get(0).color()).isEqualTo(BrandGradient.RED);
        assertThat(kof.get(kof.size() - 1).color()).isEqualTo(BrandGradient.GOLD);

        assertThat(network).hasSize(7);
        assertThat(network.get(0).color()).isEqualTo(BrandGradient.GOLD);
        assertThat(network.get(network.size() - 1).color()).isEqualTo(BrandGradient.WHITE);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.7, 1.5707963267948966, 3.141592653589793,
            4.71238898038469, 5.8})
    void градиент_остаётся_монотонным_на_каждом_кадре(double angle) {
        double previous = -1.0;
        for (int step = 0; step <= 100; step++) {
            double current = BrandGradient.progress(step / 100.0, angle);
            assertThat(current).isBetween(0.0, 1.0);
            assertThat(current).isGreaterThanOrEqualTo(previous);
            previous = current;
        }
    }
}
