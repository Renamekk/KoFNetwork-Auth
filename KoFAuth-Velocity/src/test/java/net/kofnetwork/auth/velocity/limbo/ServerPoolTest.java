package net.kofnetwork.auth.velocity.limbo;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Выбор сервера: здоровье, ёмкость и параллельные подключения.
 *
 * <p>Регрессии, которые здесь закреплены:
 * <ul>
 *   <li>выключенный сервер считался пригодным и, будучи пустым, выбирался первым;</li>
 *   <li>ёмкость Limbo не проверялась вовсе;</li>
 *   <li>загрузка бралась из числа уже подключённых, поэтому сотня одновременных
 *       заходов видела одну и ту же нулевую загрузку и целиком уходила на первый
 *       инстанс.</li>
 * </ul>
 */
class ServerPoolTest {

    private static final int CAPACITY = 50;

    private ProxyServer proxy;
    private ServerHealth health;
    private ServerPool pool;

    /** Сколько игроков «подключено» к каждому серверу. */
    private final Map<String, Integer> connected = new HashMap<>();

    @BeforeEach
    void setUp() {
        proxy = mock(ProxyServer.class);
        health = mock(ServerHealth.class);
        when(health.isUsable(anyString())).thenReturn(true);
        when(health.isRegistered(anyString())).thenReturn(true);

        ConfigurationService config = mock(ConfigurationService.class);
        when(config.getStringList(any(ConfigFile.class), anyString()))
                .thenAnswer(invocation -> switch (invocation.<String>getArgument(1)) {
                    case "limbo.servers" -> List.of("limbo-1", "limbo-2", "limbo-3");
                    case "hub.servers" -> List.of("hub-1", "hub-2");
                    default -> List.of();
                });
        when(config.getInt(any(ConfigFile.class), anyString(), anyInt()))
                .thenAnswer(invocation -> "limbo.capacity-per-instance"
                        .equals(invocation.<String>getArgument(1))
                        ? CAPACITY
                        : invocation.getArgument(2));

        pool = new ServerPool(proxy, config, health, LoggerFactory.getLogger(ServerPoolTest.class));

        register("limbo-1", 0);
        register("limbo-2", 0);
        register("limbo-3", 0);
        register("hub-1", 0);
        register("hub-2", 0);
    }

    /** Заводит сервер с заданным числом подключённых игроков. */
    private void register(String name, int players) {
        connected.put(name, players);
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo())
                .thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
        when(server.getPlayersConnected())
                .thenAnswer(invocation -> playersOf(name));
        when(proxy.getServer(name)).thenReturn(Optional.of(server));
    }

    /**
     * Набор нужного размера без единого настоящего игрока.
     *
     * <p>{@code Player} наследует десяток интерфейсов Adventure, и на свежих JDK
     * Mockito отказывается их подменять. Пулу от набора нужен только размер —
     * его и отдаём, не изображая того, чего проверка не касается.
     */
    private Set<Player> playersOf(String name) {
        int size = connected.getOrDefault(name, 0);
        return new AbstractSet<>() {

            @Override
            public Iterator<Player> iterator() {
                return Collections.emptyIterator();
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    private void setPlayers(String name, int players) {
        connected.put(name, players);
    }

    @Nested
    @DisplayName("Здоровье")
    class Health {

        /**
         * Регрессия: выключенный сервер оставался кандидатом и, будучи пустым,
         * выглядел самым привлекательным для стратегии «наименее загруженный».
         */
        @Test
        @DisplayName("недоступный сервер не выбирается, даже будучи пустым")
        void недоступныйНеВыбирается() {
            setPlayers("limbo-2", 30);
            when(health.isUsable("limbo-1")).thenReturn(false);

            Optional<ServerPool.Reservation> chosen = pool.reserveLimbo();

            assertThat(chosen).isPresent();
            assertThat(chosen.get().name())
                    .as("пустой, но выключенный limbo-1 брать нельзя")
                    .isNotEqualTo("limbo-1");
            chosen.get().release();
        }

        @Test
        @DisplayName("выводимый из эксплуатации сервер не принимает новых")
        void выводимыйНеПринимаетНовых() {
            when(health.isUsable("limbo-1")).thenReturn(false);
            when(health.isUsable("limbo-2")).thenReturn(false);

            Optional<ServerPool.Reservation> chosen = pool.reserveLimbo();

            assertThat(chosen).isPresent();
            assertThat(chosen.get().name()).isEqualTo("limbo-3");
            chosen.get().release();
        }

        @Test
        @DisplayName("когда недоступны все — выбора нет")
        void когдаНедоступныВсеВыбораНет() {
            when(health.isUsable(anyString())).thenReturn(false);

            assertThat(pool.reserveLimbo()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Ёмкость")
    class Capacity {

        @Test
        @DisplayName("заполненный инстанс уступает следующему")
        void заполненныйУступаетСледующему() {
            setPlayers("limbo-1", CAPACITY);

            Optional<ServerPool.Reservation> chosen = pool.reserveLimbo();

            assertThat(chosen).isPresent();
            assertThat(chosen.get().name()).isEqualTo("limbo-2");
            chosen.get().release();
        }

        @Test
        @DisplayName("все заполнены — выбора нет")
        void всеЗаполненыВыбораНет() {
            setPlayers("limbo-1", CAPACITY);
            setPlayers("limbo-2", CAPACITY);
            setPlayers("limbo-3", CAPACITY);

            assertThat(pool.reserveLimbo()).isEmpty();
        }

        @Test
        @DisplayName("при равной загрузке выбирается первый по конфигурации")
        void приРавнойЗагрузкеПервыйПоКонфигурации() {
            // Порядок заполнения задаёт и порядок опустошения: лишним всегда
            // становится хвостовой инстанс, и именно его можно выключить.
            Optional<ServerPool.Reservation> chosen = pool.reserveLimbo();

            assertThat(chosen).isPresent();
            assertThat(chosen.get().name()).isEqualTo("limbo-1");
            chosen.get().release();
        }
    }

    @Nested
    @DisplayName("Параллельные подключения")
    class Concurrency {

        /**
         * Регрессия: загрузка считалась по уже подключённым игрокам, а подключение
         * занимает время. Сотня одновременных заходов видела нулевую загрузку и
         * целиком уходила на первый инстанс.
         */
        @Test
        @DisplayName("бронь учитывается в загрузке до завершения подключения")
        void броньУчитываетсяВЗагрузке() {
            ServerPool.Reservation first = pool.reserveLimbo().orElseThrow();

            assertThat(pool.effectiveLoad("limbo-1")).isEqualTo(1);
            assertThat(first.name()).isEqualTo("limbo-1");

            ServerPool.Reservation second = pool.reserveLimbo().orElseThrow();
            assertThat(second.name())
                    .as("второй игрок обязан увидеть место занятым")
                    .isEqualTo("limbo-2");

            first.release();
            second.release();
            assertThat(pool.effectiveLoad("limbo-1")).isZero();
        }

        @Test
        @DisplayName("120 одновременных заходов распределяются с учётом ёмкости")
        void стоДвадцатьЗаходовРаспределяются() throws Exception {
            int players = 120;
            ExecutorService workers = Executors.newFixedThreadPool(12);
            CountDownLatch start = new CountDownLatch(1);
            List<ServerPool.Reservation> taken =
                    java.util.Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < players; i++) {
                workers.submit(() -> {
                    start.await();
                    pool.reserveLimbo().ifPresent(taken::add);
                    return null;
                });
            }
            start.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(taken)
                    .as("три инстанса по 50 мест принимают всех 120")
                    .hasSize(players);

            Map<String, Long> byServer = new HashMap<>();
            taken.forEach(reservation ->
                    byServer.merge(reservation.name(), 1L, Long::sum));

            assertThat(byServer.values())
                    .as("ни один инстанс не должен выйти за ёмкость")
                    .allMatch(count -> count <= CAPACITY);
            assertThat(byServer)
                    .as("нагрузка обязана разойтись, а не осесть на первом инстансе")
                    .hasSizeGreaterThan(1);

            taken.forEach(ServerPool.Reservation::release);
            assertThat(pool.pendingOf("limbo-1")).isZero();
        }

        @Test
        @DisplayName("сверх суммарной ёмкости брони не выдаются")
        void сверхЁмкостиБрониНеВыдаются() throws Exception {
            int requested = 200;
            ExecutorService workers = Executors.newFixedThreadPool(12);
            CountDownLatch start = new CountDownLatch(1);
            List<ServerPool.Reservation> taken =
                    java.util.Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < requested; i++) {
                workers.submit(() -> {
                    start.await();
                    pool.reserveLimbo().ifPresent(taken::add);
                    return null;
                });
            }
            start.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(taken.size())
                    .as("три инстанса по 50 не могут принять больше 150")
                    .isLessThanOrEqualTo(3 * CAPACITY);

            taken.forEach(ServerPool.Reservation::release);
        }

        @Test
        @DisplayName("повторное освобождение брони не занижает загрузку")
        void повторноеОсвобождениеНеЗанижаетЗагрузку() {
            ServerPool.Reservation reservation = pool.reserveLimbo().orElseThrow();
            reservation.release();
            reservation.release();

            assertThat(pool.pendingOf(reservation.name())).isZero();
        }
    }

    @Nested
    @DisplayName("Хабы")
    class Hubs {

        @Test
        @DisplayName("перечень для перебора упорядочен по загрузке")
        void переченьУпорядоченПоЗагрузке() {
            setPlayers("hub-1", 40);
            setPlayers("hub-2", 5);

            List<RegisteredServer> order = pool.hubsByPreference();

            assertThat(order).hasSize(2);
            assertThat(order.get(0).getServerInfo().getName()).isEqualTo("hub-2");
        }

        @Test
        @DisplayName("недоступный хаб в перебор не попадает")
        void недоступныйХабВПереборНеПопадает() {
            when(health.isUsable("hub-1")).thenReturn(false);

            List<RegisteredServer> order = pool.hubsByPreference();

            assertThat(order).hasSize(1);
            assertThat(order.get(0).getServerInfo().getName()).isEqualTo("hub-2");
        }

        @Test
        @DisplayName("ёмкость хаба не ограничивается настройкой Limbo")
        void ёмкостьХабаНеОграничена() {
            setPlayers("hub-1", 500);
            setPlayers("hub-2", 500);

            assertThat(pool.reserveHub()).isPresent();
        }
    }
}
