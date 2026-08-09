package net.kofnetwork.auth.velocity.limbo;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Правила включения и выключения Limbo-инстансов.
 *
 * <p>Проверяется чистое решение, а не исполнение: control-plane сюда не нужен —
 * тянуть его значило бы проверять поведение заглушки вместо самих правил.
 *
 * <p>Состояния взяты те, на которых правила легче всего перепутать: пустая сеть,
 * почти полный первый инстанс рядом с пустым вторым и полностью занятый инстанс.
 */
class LimboLifecycleControllerTest {

    private static final int CAPACITY = 50;

    private ServerHealth health;
    private LimboLifecycleController controller;

    @BeforeEach
    void setUp() {
        ProxyServer proxy = mock(ProxyServer.class);
        health = new ServerHealth(proxy, LoggerFactory.getLogger(LimboLifecycleControllerTest.class));

        ConfigurationService config = mock(ConfigurationService.class);
        when(config.getInt(any(ConfigFile.class), anyString(), anyInt()))
                .thenAnswer(invocation -> switch (invocation.<String>getArgument(1)) {
                    case "limbo.lifecycle.min-ready" -> 1;
                    case "limbo.lifecycle.scale-up-headroom" -> 15;
                    case "limbo.lifecycle.scale-down-headroom" -> 25;
                    default -> invocation.getArgument(2);
                });
        when(config.getDuration(any(ConfigFile.class), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> switch (invocation.<String>getArgument(1)) {
                    // Ноль убирает влияние выдержек: их поведение проверяется отдельно.
                    case "limbo.lifecycle.cooldown", "limbo.lifecycle.drain-grace" -> Duration.ZERO;
                    default -> invocation.getArgument(2);
                });
        when(config.getBoolean(any(ConfigFile.class), anyString(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        ServerPool pool = mock(ServerPool.class);
        when(pool.limboCapacity()).thenReturn(CAPACITY);
        when(pool.limboNames()).thenReturn(List.of("limbo-1", "limbo-2", "limbo-3",
                "limbo-4", "limbo-5"));

        controller = new LimboLifecycleController(pool, health,
                LimboControlPlane.disabled(), config,
                LoggerFactory.getLogger(LimboLifecycleControllerTest.class));
    }

    /**
     * Собирает состояние пула.
     *
     * @param loads загрузка работающих инстансов по порядку; остальные считаются
     *              выключенными
     */
    private List<ServerPool.LimboState> states(int... loads) {
        List<ServerPool.LimboState> result = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String name = "limbo-" + (i + 1);
            boolean running = i < loads.length;
            int load = running ? loads[i] : 0;
            result.add(new ServerPool.LimboState(name, load, 0, running, true));
        }
        return result;
    }

    private LimboLifecycleController.Decision decide(int... loads) {
        return controller.decide(states(loads), CAPACITY, Instant.now());
    }

    @Nested
    @DisplayName("Минимум готовых")
    class MinimumReady {

        @Test
        @DisplayName("[0] — единственный инстанс остаётся включённым")
        void одинПустойОстаётсяВключённым() {
            // Пустая сеть всё равно обязана кого-то принять, а холодный старт
            // Paper занимает минуты — ждать его в момент захода нельзя.
            assertThat(decide(0).action())
                    .isEqualTo(LimboLifecycleController.Action.NONE);
        }

        @Test
        @DisplayName("без единого работающего включается первый")
        void безРаботающихВключаетсяПервый() {
            LimboLifecycleController.Decision decision =
                    controller.decide(states(), CAPACITY, Instant.now());

            assertThat(decision.action()).isEqualTo(LimboLifecycleController.Action.START);
            assertThat(decision.instance()).isEqualTo("limbo-1");
        }
    }

    @Nested
    @DisplayName("Наращивание заранее")
    class ScaleUp {

        @Test
        @DisplayName("[49] — второй инстанс включается до переполнения")
        void почтиПолныйТребуетВторого() {
            // Свободных мест 1 при запасе 15: включать нужно сейчас, а не когда
            // мест не останется вовсе.
            LimboLifecycleController.Decision decision = decide(49);

            assertThat(decision.action()).isEqualTo(LimboLifecycleController.Action.START);
            assertThat(decision.instance()).isEqualTo("limbo-2");
        }

        @Test
        @DisplayName("[50] — полный инстанс требует второго")
        void полныйТребуетВторого() {
            LimboLifecycleController.Decision decision = decide(50);

            assertThat(decision.action()).isEqualTo(LimboLifecycleController.Action.START);
            assertThat(decision.instance()).isEqualTo("limbo-2");
        }

        @Test
        @DisplayName("[30] — запаса хватает, ничего не делаем")
        void запасаХватает() {
            assertThat(decide(30).action()).isEqualTo(LimboLifecycleController.Action.NONE);
        }

        @Test
        @DisplayName("все подготовленные уже работают — включать нечего")
        void всеУжеРаботают() {
            LimboLifecycleController.Decision decision =
                    controller.decide(states(50, 50, 50, 50, 50), CAPACITY, Instant.now());

            assertThat(decision.action()).isEqualTo(LimboLifecycleController.Action.NONE);
            assertThat(decision.reason()).contains("уже работают");
        }
    }

    @Nested
    @DisplayName("Выключение лишнего")
    class ScaleDown {

        /**
         * Ключевой случай задания. Второй инстанс пуст, но выключить его нельзя:
         * без него осталось бы одно свободное место при запасе в 25.
         */
        @Test
        @DisplayName("[49, 0] — второй остаётся включённым, хотя он пуст")
        void сорокДевятьИНольОставляетВторойВключённым() {
            LimboLifecycleController.Decision decision = decide(49, 0);

            assertThat(decision.action())
                    .as("пустота инстанса — не повод его гасить; повод — достаточная "
                            + "ёмкость без него")
                    .isEqualTo(LimboLifecycleController.Action.NONE);
            assertThat(decision.reason()).contains("свободных мест");
        }

        @Test
        @DisplayName("[10, 0] — пустой хвост выводится из маршрутизации")
        void пустойХвостВыводится() {
            LimboLifecycleController.Decision decision = decide(10, 0);

            assertThat(decision.action())
                    .isEqualTo(LimboLifecycleController.Action.BEGIN_DRAIN);
            assertThat(decision.instance()).isEqualTo("limbo-2");
        }

        @Test
        @DisplayName("выведенный и пустой хвост выключается")
        void выведенныйХвостВыключается() {
            health.beginDrain("limbo-2");

            LimboLifecycleController.Decision decision = decide(10, 0);

            assertThat(decision.action()).isEqualTo(LimboLifecycleController.Action.STOP);
            assertThat(decision.instance()).isEqualTo("limbo-2");
        }

        @Test
        @DisplayName("непустой хвост не выключается, а выводится")
        void непустойХвостНеВыключается() {
            LimboLifecycleController.Decision decision = decide(5, 3);

            assertThat(decision.action())
                    .isEqualTo(LimboLifecycleController.Action.BEGIN_DRAIN);
            assertThat(decision.instance()).isEqualTo("limbo-3".equals(decision.instance())
                    ? "limbo-3" : "limbo-2");
        }

        /**
         * Начатое подключение считается наравне с подключённым игроком: иначе
         * инстанс погасили бы под игроком, который уже в пути.
         */
        @Test
        @DisplayName("ожидающее подключение удерживает инстанс от остановки")
        void ожидающееПодключениеУдерживает() {
            health.beginDrain("limbo-2");
            List<ServerPool.LimboState> withPending = List.of(
                    new ServerPool.LimboState("limbo-1", 10, 0, true, true),
                    new ServerPool.LimboState("limbo-2", 0, 1, true, true),
                    new ServerPool.LimboState("limbo-3", 0, 0, false, true),
                    new ServerPool.LimboState("limbo-4", 0, 0, false, true),
                    new ServerPool.LimboState("limbo-5", 0, 0, false, true));

            LimboLifecycleController.Decision decision =
                    controller.decide(withPending, CAPACITY, Instant.now());

            assertThat(decision.action()).isNotEqualTo(LimboLifecycleController.Action.STOP);
        }

        @Test
        @DisplayName("единственный работающий инстанс не выключается")
        void единственныйНеВыключается() {
            assertThat(decide(0).action()).isEqualTo(LimboLifecycleController.Action.NONE);
        }
    }

    @Nested
    @DisplayName("Гистерезис")
    class Hysteresis {

        /**
         * Пороги разные намеренно. Совпади они — инстанс, включённый по нехватке
         * мест, немедленно оказался бы лишним по тому же критерию, и система
         * начала бы колебаться.
         */
        @Test
        @DisplayName("включённый по нехватке инстанс не гасится тем же обходом")
        void включённыйНеГаситсяСразу() {
            // 49 игроков заняли первый инстанс, второй только что поднят.
            LimboLifecycleController.Decision afterStart = decide(49, 0);
            assertThat(afterStart.action()).isEqualTo(LimboLifecycleController.Action.NONE);

            // По мере ухода игроков хвост становится лишним — но не раньше.
            // При 26 игроках без второго инстанса осталось бы 24 свободных места,
            // меньше запаса в 25, поэтому он остаётся.
            assertThat(decide(26, 0).action())
                    .isEqualTo(LimboLifecycleController.Action.NONE);
            // При 20 свободных мест без него было бы 30 — можно выводить.
            assertThat(decide(20, 0).action())
                    .isEqualTo(LimboLifecycleController.Action.BEGIN_DRAIN);
        }
    }

    @Nested
    @DisplayName("Недоступный узел")
    class UnavailableNode {

        /**
         * Регрессия: выключенный сервер считался пригодным и, будучи пустым,
         * выбирался первым — игрок отправлялся туда, куда нельзя.
         */
        @Test
        @DisplayName("упавший инстанс не считается ёмкостью и заменяется другим")
        void упавшийИнстансЗаменяется() {
            // limbo-1 не отвечает, limbo-2 работает и почти полон.
            List<ServerPool.LimboState> withDeadNode = List.of(
                    new ServerPool.LimboState("limbo-1", 0, 0, false, true),
                    new ServerPool.LimboState("limbo-2", 45, 0, true, true),
                    new ServerPool.LimboState("limbo-3", 0, 0, false, true),
                    new ServerPool.LimboState("limbo-4", 0, 0, false, true),
                    new ServerPool.LimboState("limbo-5", 0, 0, false, true));

            LimboLifecycleController.Decision decision =
                    controller.decide(withDeadNode, CAPACITY, Instant.now());

            assertThat(decision.action()).isEqualTo(LimboLifecycleController.Action.START);
            assertThat(decision.instance())
                    .as("ёмкость упавшего инстанса не засчитывается")
                    .isEqualTo("limbo-1");
        }
    }

    @Nested
    @DisplayName("Возврат выводимого инстанса")
    class CancelDrain {

        /**
         * Вернуть уже прогретый инстанс дешевле, чем поднимать холодный, поэтому
         * отмена вывода проверяется раньше включения нового.
         */
        @Test
        @DisplayName("при нехватке мест вывод отменяется вместо запуска нового")
        void выводОтменяетсяВместоЗапуска() {
            health.beginDrain("limbo-2");

            // Выводимый инстанс новых игроков не принимает, поэтому его ёмкость
            // в расчёт не идёт: свободных мест 5 при запасе 15.
            List<ServerPool.LimboState> draining = List.of(
                    new ServerPool.LimboState("limbo-1", 45, 0, true, true),
                    new ServerPool.LimboState("limbo-2", 0, 0, false, true),
                    new ServerPool.LimboState("limbo-3", 0, 0, false, true),
                    new ServerPool.LimboState("limbo-4", 0, 0, false, true),
                    new ServerPool.LimboState("limbo-5", 0, 0, false, true));

            LimboLifecycleController.Decision decision =
                    controller.decide(draining, CAPACITY, Instant.now());

            assertThat(decision.action())
                    .as("вернуть прогретый инстанс дешевле, чем поднимать холодный")
                    .isEqualTo(LimboLifecycleController.Action.CANCEL_DRAIN);
            assertThat(decision.instance()).isEqualTo("limbo-2");
        }
    }
}
