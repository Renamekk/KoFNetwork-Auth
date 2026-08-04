package net.kofnetwork.auth.paper.listener;

import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Возврат игрока на разрешённую точку в Limbo. */
class LimboProtectionListenerTest {

    /** Мир не нужен: проверяется только перенос координат и углов. */
    private static Location at(double x, double y, double z, float yaw, float pitch) {
        return new Location(null, x, y, z, yaw, pitch);
    }

    @Test
    @DisplayName("возврат на точку сохраняет направление взгляда")
    void возврат_на_точку_сохраняет_направление_взгляда() {
        Location anchor = at(0.5, 100, 0.5, 0f, 0f);
        Location requested = at(40.0, 100, 40.0, 137.5f, -22.5f);

        Location corrected = LimboProtectionListener.keepingView(anchor, requested);

        // Позиция — из разрешённой точки: игрок за радиус не уходит.
        assertThat(corrected.getX()).isEqualTo(anchor.getX());
        assertThat(corrected.getY()).isEqualTo(anchor.getY());
        assertThat(corrected.getZ()).isEqualTo(anchor.getZ());

        // А вот взгляд — тот, что запросил клиент. Если вернуть и его, камера
        // будет отбрасываться назад несколько раз в секунду, и игрок увидит
        // не ограничение зоны, а намертво зависший клиент.
        assertThat(corrected.getYaw()).isEqualTo(137.5f);
        assertThat(corrected.getPitch()).isEqualTo(-22.5f);
    }

    @Test
    @DisplayName("исходная точка не изменяется")
    void исходная_точка_не_изменяется() {
        Location anchor = at(0.5, 100, 0.5, 0f, 0f);

        LimboProtectionListener.keepingView(anchor, at(1, 1, 1, 90f, 45f));

        // Якорь переиспользуется на каждое движение каждого игрока: правка
        // на месте развернула бы всех, кого держит эта же точка.
        assertThat(anchor.getYaw()).isEqualTo(0f);
        assertThat(anchor.getPitch()).isEqualTo(0f);
    }
}
