package net.kofnetwork.auth.webapi.health;

import net.kofnetwork.auth.core.KoFAuthCore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Проверка работоспособности для {@code /actuator/health}.
 *
 * <p><b>Отсутствие Redis не делает сервис нездоровым.</b> Кэш — ускоритель:
 * без него система работает по MySQL, теряя скорость и межпроцессную связность,
 * но продолжая обслуживать вход. Пометить такое состояние как {@code DOWN}
 * значило бы заставить оркестратор перезапускать исправный контейнер и
 * выводить его из балансировки на ровном месте.
 *
 * <p>Недоступность MySQL — другое дело: без неё аутентификация невозможна,
 * и трафик на такой узел слать нельзя.
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
        boolean cacheUp = core.cache().isAvailable();

        Health.Builder builder = databaseUp ? Health.up() : Health.down();

        return builder
                .withDetail("version", core.version())
                .withDetail("database", databaseUp ? "up" : "down")
                .withDetail("databaseConnections", pool.active() + "/" + pool.total())
                .withDetail("databaseAwaiting", pool.awaiting())
                .withDetail("cache", cacheUp ? core.cache().providerName() : "degraded")
                .build();
    }
}
