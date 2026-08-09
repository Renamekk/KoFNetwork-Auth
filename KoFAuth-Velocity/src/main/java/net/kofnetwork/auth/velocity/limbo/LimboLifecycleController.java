package net.kofnetwork.auth.velocity.limbo;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Держит включённым столько Limbo-инстансов, сколько нужно, и ни одним больше.
 *
 * <p>Инстансы не создаются — они подготовлены заранее и перечислены в конфигурации.
 * Здесь решается только, какие из них должны работать прямо сейчас, а исполняет решение
 * {@link LimboControlPlane}: у прокси нет и не должно быть прямого доступа к Docker.
 *
 * <h2>Правила</h2>
 * <ul>
 *   <li><b>Минимум один готов всегда.</b> Пустая сеть всё равно обязана кого-то принять,
 *       а холодный старт Paper занимает минуты — ждать его в момент захода нельзя.</li>
 *   <li><b>Следующий включается заранее.</b> Порогом служит запас свободных мест, а не
 *       факт переполнения: к моменту, когда мест не осталось, включать уже поздно.</li>
 *   <li><b>Лишний выключается только хвостовой</b> — и только когда он пуст, к нему нет
 *       начатых подключений, а оставшейся ёмкости хватает с запасом.</li>
 * </ul>
 *
 * <h2>Почему запасы разные</h2>
 * <p>Порог включения ({@code scale-up-headroom}) меньше порога выключения
 * ({@code scale-down-headroom}). Это гистерезис, и без него система колеблется: инстанс,
 * включённый при нехватке мест, тут же оказался бы лишним по тому же самому критерию и
 * был бы выключен, после чего мест снова не хватило бы.
 *
 * <p>Именно это правило отвечает на состояние {@code [49, 0]}: первый Limbo занят на 49
 * из 50, второй пуст. Выключение второго оставило бы одно свободное место — меньше
 * запаса, — поэтому он остаётся включённым, хотя на нём никого нет. Пустота инстанса
 * сама по себе не повод его гасить; поводом служит достаточная ёмкость без него.
 *
 * <h2>Безопасное выведение</h2>
 * <p>Перед остановкой инстанс переводится в режим вывода: маршрутизация перестаёт его
 * выбирать, а уже подключённые доигрывают. Остановка выполняется, лишь когда он опустел
 * и выдержал паузу — иначе игрок, начавший подключение за миг до решения, оказался бы
 * на выключающемся сервере.
 */
public final class LimboLifecycleController {

    private final ServerPool pool;
    private final ServerHealth health;
    private final LimboControlPlane controlPlane;
    private final ConfigurationService config;
    private final Logger logger;

    /** Когда над инстансом последний раз выполнялось действие. Ключ — имя. */
    private final Map<String, Instant> lastAction = new ConcurrentHashMap<>();

    /** Когда инстанс переведён в режим вывода. */
    private final Map<String, Instant> drainingSince = new ConcurrentHashMap<>();

    /** Обходы не должны накладываться друг на друга. */
    private final AtomicBoolean sweeping = new AtomicBoolean();

    public LimboLifecycleController(@NotNull ServerPool pool,
                                    @NotNull ServerHealth health,
                                    @NotNull LimboControlPlane controlPlane,
                                    @NotNull ConfigurationService config,
                                    @NotNull Logger logger) {
        this.pool = pool;
        this.health = health;
        this.controlPlane = controlPlane;
        this.config = config;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ настройки

    private int minReady() {
        return Math.max(1, config.getInt(ConfigFile.VELOCITY, "limbo.lifecycle.min-ready", 1));
    }

    private int scaleUpHeadroom() {
        return Math.max(1, config.getInt(ConfigFile.VELOCITY,
                "limbo.lifecycle.scale-up-headroom", 15));
    }

    /**
     * Запас, который обязан остаться после выключения.
     *
     * <p>Не меньше порога включения плюс единица: равные пороги означают отсутствие
     * гистерезиса и, как следствие, включение и выключение по кругу.
     */
    private int scaleDownHeadroom() {
        int configured = config.getInt(ConfigFile.VELOCITY,
                "limbo.lifecycle.scale-down-headroom", 25);
        return Math.max(scaleUpHeadroom() + 1, configured);
    }

    private Duration cooldown() {
        return config.getDuration(ConfigFile.VELOCITY, "limbo.lifecycle.cooldown",
                Duration.ofSeconds(60));
    }

    private Duration drainGrace() {
        return config.getDuration(ConfigFile.VELOCITY, "limbo.lifecycle.drain-grace",
                Duration.ofSeconds(30));
    }

    // ------------------------------------------------------------------ обход

    /**
     * Один шаг регулирования. Вызывается планировщиком.
     *
     * <p>За обход выполняется не более одного действия. Включать два инстанса сразу
     * незачем — запас рассчитан на один шаг, — а выключать несколько подряд опасно:
     * второе решение принималось бы по данным, снятым до первого.
     */
    public void sweep() {
        if (!controlPlane.isEnabled()) {
            return;
        }
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            Decision decision = decide(pool.limboStates(), pool.limboCapacity(), Instant.now());
            apply(decision);
        } catch (RuntimeException e) {
            logger.error("Ошибка регулирования Limbo-инстансов", e);
        } finally {
            sweeping.set(false);
        }
    }

    private void apply(Decision decision) {
        switch (decision.action()) {
            case NONE -> {
            }
            case START -> start(decision.instance(), decision.reason());
            case BEGIN_DRAIN -> beginDrain(decision.instance(), decision.reason());
            case STOP -> stop(decision.instance(), decision.reason());
            case CANCEL_DRAIN -> cancelDrain(decision.instance(), decision.reason());
        }
    }

    // ------------------------------------------------------------------ решение

    /** Что делать. */
    public enum Action {
        NONE, START, BEGIN_DRAIN, STOP, CANCEL_DRAIN
    }

    /** @param instance имя инстанса; пусто при {@link Action#NONE} */
    public record Decision(@NotNull Action action, @NotNull String instance, @NotNull String reason) {

        public static @NotNull Decision none(@NotNull String reason) {
            return new Decision(Action.NONE, "", reason);
        }
    }

    /**
     * Чистое решение по состоянию пула.
     *
     * <p>Отделено от исполнения намеренно: правила масштабирования — это то, что нужно
     * проверять на состояниях вроде {@code [0]}, {@code [49, 0]} и {@code [50]}, и
     * тянуть ради этого control-plane в тест значило бы проверять не правила, а
     * заглушки.
     *
     * @param states   инстансы в порядке конфигурации
     * @param capacity вместимость одного инстанса
     */
    public @NotNull Decision decide(@NotNull List<ServerPool.LimboState> states,
                                    int capacity,
                                    @NotNull Instant now) {
        if (states.isEmpty()) {
            return Decision.none("перечень Limbo пуст");
        }

        List<ServerPool.LimboState> ready = states.stream()
                .filter(ServerPool.LimboState::ready)
                .toList();

        int readyCapacity = ready.size() * capacity;
        int players = states.stream().mapToInt(ServerPool.LimboState::load).sum();
        int free = readyCapacity - players;

        // --- 1. Минимум готовых. Важнее всего: без единого Limbo сеть не принимает никого.
        if (ready.size() < minReady()) {
            Optional<ServerPool.LimboState> candidate = firstStoppable(states, now);
            if (candidate.isPresent()) {
                return new Decision(Action.START, candidate.get().name(),
                        "готовых инстансов " + ready.size() + ", требуется " + minReady());
            }
        }

        // --- 2. Отменяем вывод, если ёмкость снова понадобилась. Проверяется до
        //        включения нового: вернуть выводимый инстанс дешевле, чем поднять
        //        холодный, и он уже прогрет.
        Optional<ServerPool.LimboState> draining = states.stream()
                .filter(state -> health.isDraining(state.name()))
                .findFirst();
        if (draining.isPresent() && free < scaleUpHeadroom()) {
            return new Decision(Action.CANCEL_DRAIN, draining.get().name(),
                    "свободных мест " + free + ", запас требуется " + scaleUpHeadroom());
        }

        // --- 3. Мест мало — включаем следующий заранее.
        if (free < scaleUpHeadroom()) {
            Optional<ServerPool.LimboState> candidate = firstStoppable(states, now);
            if (candidate.isEmpty()) {
                return Decision.none("свободных мест " + free
                        + ", но все подготовленные инстансы уже работают");
            }
            return new Decision(Action.START, candidate.get().name(),
                    "свободных мест " + free + " при запасе " + scaleUpHeadroom());
        }

        // --- 4. Хвостовой инстанс лишний? Проверяем строго по трём условиям.
        return considerScaleDown(states, ready, capacity, players, now);
    }

    /**
     * Выключение хвостового инстанса.
     *
     * <p>Три условия и все обязательны: инстанс пуст, к нему нет начатых подключений,
     * а без него запас свободных мест остаётся достаточным. Именно третье условие
     * оставляет включённым второй Limbo при {@code [49, 0]}.
     */
    private Decision considerScaleDown(List<ServerPool.LimboState> states,
                                       List<ServerPool.LimboState> ready,
                                       int capacity,
                                       int players,
                                       Instant now) {
        if (ready.size() <= minReady()) {
            return Decision.none("работает минимально допустимое число инстансов");
        }

        // Хвостовой — последний работающий по порядку конфигурации. Порядок тот же,
        // что и у выбора при заходе, поэтому пустеет именно он.
        ServerPool.LimboState tail = ready.get(ready.size() - 1);

        int freeWithoutTail = (ready.size() - 1) * capacity - players;
        if (freeWithoutTail < scaleDownHeadroom()) {
            return Decision.none("без " + tail.name() + " осталось бы " + freeWithoutTail
                    + " свободных мест при требуемом запасе " + scaleDownHeadroom());
        }
        if (!tail.isEmpty()) {
            // Уже выводится — просто ждём, пока доиграют.
            return health.isDraining(tail.name())
                    ? Decision.none(tail.name() + " выводится, на нём ещё " + tail.load())
                    : new Decision(Action.BEGIN_DRAIN, tail.name(),
                            "инстанс лишний, но на нём " + tail.load() + " игроков");
        }
        if (!health.isDraining(tail.name())) {
            // Пустой инстанс сначала выводится из маршрутизации, и только потом гасится.
            // Иначе игрок, начавший подключение мгновением раньше, попадёт на сервер,
            // который уже выключают.
            return new Decision(Action.BEGIN_DRAIN, tail.name(), "инстанс пуст и лишний");
        }
        Instant since = drainingSince.get(tail.name());
        if (since != null && Duration.between(since, now).compareTo(drainGrace()) < 0) {
            return Decision.none(tail.name() + " опустел, выдерживаем паузу перед остановкой");
        }
        if (!cooledDown(tail.name(), now)) {
            return Decision.none(tail.name() + ": не истёк интервал между действиями");
        }
        return new Decision(Action.STOP, tail.name(), "инстанс пуст, выведен и лишний");
    }

    /** Первый по порядку инстанс, который сейчас не работает и которого можно включить. */
    private Optional<ServerPool.LimboState> firstStoppable(List<ServerPool.LimboState> states,
                                                            Instant now) {
        List<ServerPool.LimboState> stopped = new ArrayList<>();
        for (ServerPool.LimboState state : states) {
            if (!state.ready() && cooledDown(state.name(), now)) {
                stopped.add(state);
            }
        }
        return stopped.isEmpty() ? Optional.empty() : Optional.of(stopped.get(0));
    }

    private boolean cooledDown(String instance, Instant now) {
        Instant last = lastAction.get(instance);
        return last == null || Duration.between(last, now).compareTo(cooldown()) >= 0;
    }

    // ------------------------------------------------------------------ исполнение

    private void start(String instance, String reason) {
        lastAction.put(instance, Instant.now());
        logger.info("Включаю Limbo {}: {}", instance, reason);
        // Вывод отменяется сразу: инстанс снова нужен, и держать его вне маршрутизации
        // до подтверждения запуска значило бы терять уже готовую ёмкость.
        health.endDrain(instance);
        drainingSince.remove(instance);

        controlPlane.start(instance).whenComplete((accepted, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(accepted)) {
                logger.error("Не удалось включить Limbo {}: запрос не принят control-plane",
                        instance, failure);
                return;
            }
            // Готовность подтверждает ping, а не ответ службы: «контейнер запущен» и
            // «сервер принимает игроков» — разные события, между ними минуты.
            logger.info("Control-plane принял запуск {}; жду готовности по ping", instance);
        });
    }

    private void beginDrain(String instance, String reason) {
        if (health.isDraining(instance)) {
            return;
        }
        logger.info("Вывожу Limbo {} из маршрутизации: {}", instance, reason);
        health.beginDrain(instance);
        drainingSince.put(instance, Instant.now());
    }

    private void cancelDrain(String instance, String reason) {
        logger.info("Возвращаю Limbo {} в маршрутизацию: {}", instance, reason);
        health.endDrain(instance);
        drainingSince.remove(instance);
    }

    private void stop(String instance, String reason) {
        lastAction.put(instance, Instant.now());
        logger.info("Выключаю Limbo {}: {}", instance, reason);

        controlPlane.stop(instance).whenComplete((accepted, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(accepted)) {
                logger.error("Не удалось выключить Limbo {}; возвращаю в маршрутизацию",
                        instance, failure);
                health.endDrain(instance);
                drainingSince.remove(instance);
                return;
            }
            health.assumeDown(instance);
            drainingSince.remove(instance);
        });
    }

    /** Инстансы, которые сейчас выводятся, — для {@code /auth info}. */
    public @NotNull List<String> drainingInstances() {
        return pool.limboNames().stream().filter(health::isDraining).toList();
    }
}
