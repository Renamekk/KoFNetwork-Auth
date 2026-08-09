package net.kofnetwork.auth.webapi.health;

import net.kofnetwork.auth.core.KoFAuthCore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Проверка работоспособности для {@code /actuator/health}.
 *
 * <p><b>Отказ хранилища состояния делает узел неготовым.</b> Раньше здесь стояло
 * обратное рассуждение: Redis — ускоритель, без него система работает по MySQL,
 * поэтому пометка {@code DOWN} только заставит оркестратор дёргать исправный
 * контейнер. Рассуждение было бы верным, если бы в Redis лежал кэш. Но там лежит
 * состояние: машина входа, привязка UUID к сессии, счётчики ограничения скорости,
 * межпроцессная синхронизация. Копии в MySQL у них нет.
 *
 * <p>Узел, потерявший это состояние, не «работает медленнее» — он не может отличить
 * вошедшего игрока от невошедшего и перестаёт считать лимиты. Оставлять его в
 * балансировке значит продолжать слать на него трафик, который он обслужит
 * неправильно, и не показать эксплуатации, что случилось.
 *
 * <p>Недоступность MySQL — та же история и по той же причине: без неё аутентификация
 * невозможна.
 */
@Component("kofauth")
public class KoFAuthHealthIndicator implements HealthIndicator {

    private final KoFAuthCore core;

    public KoFAuthHealthIndicator(KoFAuthCore core) {
        this.core = core;
    }

    @Override
    public Health health() {
        var pool = core.database().snapshot();
        boolean databaseUp = core.database().isHealthy();
        boolean stateStoreUp = core.cache().isAvailable();
        boolean ready = core.isReady();

        Health.Builder builder = ready ? Health.up() : Health.down();

        return builder
                .withDetail("version", core.version())
                .withDetail("ready", ready)
                .withDetail("detail", core.readinessDetail())
                .withDetail("database", databaseUp ? "up" : "down")
                .withDetail("databaseConnections", pool.active() + "/" + pool.total())
                .withDetail("databaseAwaiting", pool.awaiting())
                .withDetail("stateStore", core.cache().providerName())
                .withDetail("stateStoreUp", stateStoreUp)
                // Общее ли это хранилище для всех процессов. При локальном узлы
                // не видят сессий друг друга — на сети из нескольких прокси это
                // ошибка развёртывания, которую видно только здесь.
                .withDetail("stateStoreShared", core.cache().isDistributed())
                .build();
    }
}
