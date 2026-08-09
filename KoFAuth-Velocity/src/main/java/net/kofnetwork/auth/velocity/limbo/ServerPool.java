package net.kofnetwork.auth.velocity.limbo;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Пул серверов сети: куда пускать игрока прямо сейчас.
 *
 * <p><b>Что здесь исправлено по сравнению с прежним выбором.</b> Прежний маршрутизатор
 * считал доступным любой сервер, просто перечисленный в {@code velocity.toml}. Из этого
 * следовало три беды подряд:
 * <ul>
 *   <li>выключенный сервер выглядел самым привлекательным — у него ноль игроков, а
 *       стратегия {@code least-players} выбирает наименее загруженный. Первый же игрок
 *       отправлялся ровно туда, куда нельзя;</li>
 *   <li>готовность не проверялась вовсе: запущенный, но ещё не принимающий соединения
 *       Paper считался таким же пригодным, как прогретый;</li>
 *   <li>число игроков бралось из {@code getPlayersConnected()}, то есть учитывались
 *       только уже подключённые. При массовом заходе сотня одновременных подключений
 *       видела одну и ту же нулевую загрузку и целиком уходила на один инстанс.</li>
 * </ul>
 *
 * <p>Здесь выбор опирается на три величины: работает ли сервер (по
 * {@link ServerHealth}), сколько на нём игроков и сколько подключений к нему уже
 * начато, но ещё не завершилось. Последнее — {@link Reservation} — и снимает гонку
 * массового захода: бронь учитывается в загрузке до того, как игрок доедет.
 */
public final class ServerPool {

    /** Сколько игроков вмещает один Limbo, если конфигурация не говорит иного. */
    public static final int DEFAULT_LIMBO_CAPACITY = 50;

    private final ProxyServer proxy;
    private final ConfigurationService config;
    private final ServerHealth health;
    private final Logger logger;

    /** Начатые, но не завершённые подключения. Ключ — имя сервера. */
    private final Map<String, AtomicInteger> pending = new ConcurrentHashMap<>();

    public ServerPool(@NotNull ProxyServer proxy,
                      @NotNull ConfigurationService config,
                      @NotNull ServerHealth health,
                      @NotNull Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.health = health;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ состав пулов

    /** Имена Limbo-серверов из конфигурации. */
    public @NotNull List<String> limboNames() {
        return names("limbo.servers");
    }

    /** Имена хабов из конфигурации. */
    public @NotNull List<String> hubNames() {
        return names("hub.servers");
    }

    private List<String> names(String path) {
        List<String> configured = config.getStringList(ConfigFile.VELOCITY, path);
        return configured.isEmpty() ? List.of() : configured;
    }

    /** Вместимость одного Limbo. */
    public int limboCapacity() {
        int configured = config.getInt(ConfigFile.VELOCITY, "limbo.capacity-per-instance",
                DEFAULT_LIMBO_CAPACITY);
        return Math.max(1, configured);
    }

    public boolean isLimbo(@NotNull String serverName) {
        return limboNames().stream().anyMatch(name -> name.equalsIgnoreCase(serverName));
    }

    public boolean isHub(@NotNull String serverName) {
        return hubNames().stream().anyMatch(name -> name.equalsIgnoreCase(serverName));
    }

    // ------------------------------------------------------------------ выбор

    /**
     * Бронирует место в Limbo.
     *
     * <p>Возвращается именно бронь, а не сервер: подключение занимает время, и пока оно
     * идёт, следующий игрок обязан видеть это место занятым. Бронь снимается в
     * {@link Reservation#release()} — и при успехе, и при отказе.
     */
    public @NotNull Optional<Reservation> reserveLimbo() {
        int capacity = limboCapacity();
        return reserve(limboNames(), capacity, "limbo");
    }

    /** Бронирует место на хабе. Вместимость хаба не ограничивается: её задаёт сам сервер. */
    public @NotNull Optional<Reservation> reserveHub() {
        return reserve(hubNames(), Integer.MAX_VALUE, "hub");
    }

    /**
     * Список хабов в порядке предпочтения — для перебора при отказе.
     *
     * <p>Один выбранный хаб недостаточен: сервер может отвергнуть подключение уже после
     * того, как ответил на ping. Прежняя версия отправляла игрока
     * {@code fireAndForget()} и не узнавала об отказе вовсе — игрок оставался в Limbo
     * без единого сообщения.
     */
    public @NotNull List<RegisteredServer> hubsByPreference() {
        return candidates(hubNames()).stream()
                .filter(server -> health.isUsable(server.getServerInfo().getName()))
                .sorted(Comparator.comparingInt(this::effectiveLoad))
                .toList();
    }

    /**
     * Выбирает сервер и <em>атомарно</em> занимает на нём место.
     *
     * <p><b>Почему проверка и бронь неразделимы.</b> Раздельные «посмотреть загрузку»
     * и «занять место» оставляют окно, в которое помещается второй заход: оба видят
     * сервер незаполненным, оба бронируют, и вместимость оказывается превышена.
     * На массовом заходе это не гипотеза, а норма — именно тогда и подключаются сотнями.
     * Поэтому бронь ставится сравнением с обменом на счётчике, а порядок перебора
     * задаёт лишь предпочтение: не вышло здесь — пробуем следующий.
     */
    private Optional<Reservation> reserve(List<String> names, int capacity, String kind) {
        if (names.isEmpty()) {
            logger.error("В velocity.yml не указано ни одного сервера типа {}", kind);
            return Optional.empty();
        }

        List<RegisteredServer> usable = new ArrayList<>();
        for (RegisteredServer server : candidates(names)) {
            if (health.isUsable(server.getServerInfo().getName())) {
                usable.add(server);
            }
        }

        if (usable.isEmpty()) {
            logger.warn("Нет доступного сервера типа {}: ни один не отвечает", kind);
            return Optional.empty();
        }

        // Наименее загруженный с учётом брони. При равенстве порядок определяется
        // конфигурацией, поэтому заполняется limbo-1, затем limbo-2 и так далее —
        // это и позволяет лишним инстансам пустеть и выключаться.
        Comparator<RegisteredServer> byLoadThenOrder =
                Comparator.<RegisteredServer>comparingInt(this::effectiveLoad)
                        .thenComparingInt(server -> names.indexOf(server.getServerInfo().getName()));

        usable.sort(byLoadThenOrder);

        for (RegisteredServer server : usable) {
            Optional<Reservation> reservation = tryAcquire(server, capacity);
            if (reservation.isPresent()) {
                return reservation;
            }
        }

        logger.warn("Нет доступного сервера типа {}: все заполнены", kind);
        return Optional.empty();
    }

    /**
     * Занимает место, если оно ещё есть.
     *
     * <p>Число подключённых читается один раз: меняется оно медленно, а состязание
     * идёт именно за счётчик брони. Его увеличение выполняется сравнением с обменом,
     * поэтому суммарное число занятых мест не превышает вместимости даже когда
     * бронируют десятки потоков разом.
     */
    private Optional<Reservation> tryAcquire(RegisteredServer server, int capacity) {
        String name = server.getServerInfo().getName();
        int connected = server.getPlayersConnected().size();
        AtomicInteger counter = pending.computeIfAbsent(name, ignored -> new AtomicInteger());

        while (true) {
            int reserved = counter.get();
            if (connected + reserved >= capacity) {
                return Optional.empty();
            }
            if (counter.compareAndSet(reserved, reserved + 1)) {
                return Optional.of(new Reservation(server, name));
            }
        }
    }

    private List<RegisteredServer> candidates(List<String> names) {
        List<RegisteredServer> found = new ArrayList<>(names.size());
        for (String name : names) {
            proxy.getServer(name).ifPresent(found::add);
        }
        if (found.isEmpty() && !names.isEmpty()) {
            logger.error("Ни один из серверов {} не зарегистрирован в velocity.toml", names);
        }
        return found;
    }

    /** Подключённые игроки плюс подключения в процессе. */
    public int effectiveLoad(@NotNull RegisteredServer server) {
        return server.getPlayersConnected().size() + pendingOf(server.getServerInfo().getName());
    }

    /** Загрузка сервера по имени; для незарегистрированного — только бронь. */
    public int effectiveLoad(@NotNull String serverName) {
        return proxy.getServer(serverName)
                .map(this::effectiveLoad)
                .orElseGet(() -> pendingOf(serverName));
    }

    public int pendingOf(@NotNull String serverName) {
        AtomicInteger counter = pending.get(serverName);
        return counter == null ? 0 : Math.max(0, counter.get());
    }

    /** Бронирует место на конкретном сервере — для повторной попытки при отказе. */
    public @NotNull Reservation acquire(@NotNull RegisteredServer server) {
        String name = server.getServerInfo().getName();
        pending.computeIfAbsent(name, ignored -> new AtomicInteger()).incrementAndGet();
        return new Reservation(server, name);
    }

    /**
     * Занятое место на время подключения.
     *
     * <p>Освобождать обязательно и ровно один раз — отсюда защита от повторного вызова:
     * потерянная бронь навсегда завышает загрузку сервера, а лишнее освобождение
     * занижает её, и обе ошибки ломают выбор одинаково незаметно.
     */
    public final class Reservation implements AutoCloseable {

        private final RegisteredServer server;
        private final String name;
        private boolean released;

        private Reservation(RegisteredServer server, String name) {
            this.server = server;
            this.name = name;
        }

        public @NotNull RegisteredServer server() {
            return server;
        }

        public @NotNull String name() {
            return name;
        }

        public synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            AtomicInteger counter = pending.get(name);
            if (counter != null) {
                counter.updateAndGet(value -> Math.max(0, value - 1));
            }
        }

        @Override
        public void close() {
            release();
        }
    }

    // ------------------------------------------------------------------ сводка

    /**
     * Загрузка Limbo-инстансов в порядке конфигурации.
     *
     * <p>Нужна управлению жизненным циклом: именно по ней решается, хватает ли
     * свободных мест и можно ли выключить хвостовой инстанс.
     */
    public @NotNull List<LimboState> limboStates() {
        List<String> names = limboNames();
        List<LimboState> states = new ArrayList<>(names.size());
        for (String name : names) {
            RegisteredServer server = proxy.getServer(name).orElse(null);
            int connected = server == null ? 0 : server.getPlayersConnected().size();
            states.add(new LimboState(name, connected, pendingOf(name),
                    health.isUsable(name), health.isRegistered(name)));
        }
        return states;
    }

    /**
     * @param ready      отвечает ли сервер и не выводится ли из эксплуатации
     * @param registered известен ли он прокси вообще
     */
    public record LimboState(@NotNull String name,
                             int connected,
                             int pending,
                             boolean ready,
                             boolean registered) {

        public int load() {
            return connected + pending;
        }

        public boolean isEmpty() {
            return connected == 0 && pending == 0;
        }
    }

    /** Сервер по имени, если он зарегистрирован. */
    public @Nullable RegisteredServer server(@NotNull String name) {
        return proxy.getServer(name).orElse(null);
    }

    // ------------------------------------------------------------------ брони маршрутизации

    /** Брони, выданные при выборе сервера и ещё не закрытые. Ключ — игрок. */
    private final Map<UUID, Reservation> routing = new ConcurrentHashMap<>();

    /**
     * Запоминает бронь на время, пока игрок доезжает до сервера.
     *
     * <p>Отпустить её сразу после выбора значило бы вернуть исходную беду:
     * подключение занимает время, и следующий игрок снова увидел бы место
     * свободным. Бронь закрывается по факту — когда игрок подключился
     * ({@link #routingCompleted}) или отключился, не доехав.
     *
     * <p>Прежняя бронь того же игрока закрывается: маршрутизация могла сработать
     * дважды подряд, и вторая отменяет первую.
     */
    public void trackRouting(@NotNull UUID playerUuid, @NotNull Reservation reservation) {
        Reservation previous = routing.put(playerUuid, reservation);
        if (previous != null) {
            previous.release();
        }
    }

    /** Закрывает бронь игрока. Безопасно вызывать, даже если её не было. */
    public void routingCompleted(@NotNull UUID playerUuid) {
        Reservation reservation = routing.remove(playerUuid);
        if (reservation != null) {
            reservation.release();
        }
    }
}
