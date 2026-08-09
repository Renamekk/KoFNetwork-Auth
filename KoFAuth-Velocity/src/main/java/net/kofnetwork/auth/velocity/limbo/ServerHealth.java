package net.kofnetwork.auth.velocity.limbo;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Кто из бэкендов сейчас отвечает.
 *
 * <p><b>Зачем это понадобилось.</b> Прежний выбор сервера считал пригодным любой,
 * зарегистрированный в {@code velocity.toml}. Выключенный инстанс от этого не переставал
 * быть кандидатом — более того, становился самым привлекательным: у него ноль игроков,
 * а стратегия предпочитает наименее загруженного. Первый же вход отправлял игрока на
 * несуществующий сервер.
 *
 * <p>Признак живости берётся из ping, а не из таблицы {@code servers}: heartbeat в базу
 * пишет сам бэкенд и продолжает писать ещё какое-то время после того, как перестал
 * принимать соединения. Ping отвечает на тот же вопрос, что интересует прокси, — примет
 * ли этот сервер игрока прямо сейчас.
 *
 * <p><b>Порог, а не одно измерение.</b> Один потерянный ответ — это сетевая помеха,
 * и выводить из эксплуатации по нему значит дёргать маршрутизацию на ровном месте.
 * Сервер считается упавшим после нескольких подряд неудач и возвращается в строй
 * с первого успешного ответа: возвращаться нужно быстро, уходить — осторожно.
 */
public final class ServerHealth {

    /** Сколько неудачных ping подряд означает отказ. */
    private static final int FAILURE_THRESHOLD = 3;

    private final ProxyServer proxy;
    private final Logger logger;

    private final Map<String, Status> statuses = new ConcurrentHashMap<>();

    /** Серверы, выводимые из эксплуатации: новых игроков не принимают. */
    private final Set<String> draining = new CopyOnWriteArraySet<>();

    public ServerHealth(@NotNull ProxyServer proxy, @NotNull Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    private static final class Status {
        volatile boolean alive;
        volatile int consecutiveFailures;
        volatile Instant lastSuccess = Instant.EPOCH;

        Status(boolean initiallyAlive) {
            this.alive = initiallyAlive;
        }
    }

    /**
     * Можно ли направлять сюда новых игроков.
     *
     * <p>Незарегистрированный сервер непригоден: имя из конфигурации, которого нет в
     * {@code velocity.toml}, — это опечатка, а не сервер.
     */
    public boolean isUsable(@NotNull String serverName) {
        if (draining.contains(serverName)) {
            return false;
        }
        if (!isRegistered(serverName)) {
            return false;
        }
        Status status = statuses.get(serverName);
        // До первой проверки сервер считается пригодным: иначе сразу после старта
        // прокси не пустил бы никого никуда, пока не отработает первый обход.
        return status == null || status.alive;
    }

    public boolean isRegistered(@NotNull String serverName) {
        return proxy.getServer(serverName).isPresent();
    }

    /** Отвечал ли сервер на последнем обходе. */
    public boolean isAlive(@NotNull String serverName) {
        Status status = statuses.get(serverName);
        return status == null || status.alive;
    }

    public @NotNull Instant lastSuccess(@NotNull String serverName) {
        Status status = statuses.get(serverName);
        return status == null ? Instant.EPOCH : status.lastSuccess;
    }

    /** Помечает сервер выводимым из эксплуатации: игроки на нём остаются, новых нет. */
    public void beginDrain(@NotNull String serverName) {
        if (draining.add(serverName)) {
            logger.info("Сервер {} выводится из эксплуатации: новых игроков не принимает",
                    serverName);
        }
    }

    public void endDrain(@NotNull String serverName) {
        if (draining.remove(serverName)) {
            logger.info("Сервер {} снова принимает игроков", serverName);
        }
    }

    public boolean isDraining(@NotNull String serverName) {
        return draining.contains(serverName);
    }

    /**
     * Обходит серверы и обновляет признак живости.
     *
     * <p>Вызывается планировщиком. Ping асинхронный, поэтому обход не блокирует поток
     * и не растягивается на сумму таймаутов.
     */
    public void probe(@NotNull Collection<String> serverNames, @NotNull Duration timeout) {
        for (String name : serverNames) {
            RegisteredServer server = proxy.getServer(name).orElse(null);
            if (server == null) {
                // Имя из конфигурации без записи в velocity.toml — это либо
                // подготовленный, но ещё не подключённый инстанс, либо опечатка.
                // Ни то ни другое не повод писать предупреждение каждые десять
                // секунд: непригодным такой сервер считается и без записи в лог,
                // а о недостающих именах сообщается один раз при запуске.
                statuses.computeIfAbsent(name, ignored -> new Status(false)).alive = false;
                continue;
            }
            server.ping()
                    .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .whenComplete((ping, failure) -> {
                        if (failure == null) {
                            markUp(name);
                        } else {
                            markDown(name, failure.getClass().getSimpleName());
                        }
                    });
        }
    }

    private void markUp(String name) {
        Status status = statuses.computeIfAbsent(name, ignored -> new Status(true));
        boolean wasDown = !status.alive;
        status.consecutiveFailures = 0;
        status.alive = true;
        status.lastSuccess = Instant.now();
        if (wasDown) {
            logger.info("Сервер {} снова отвечает", name);
        }
    }

    private void markDown(String name, String reason) {
        Status status = statuses.computeIfAbsent(name, ignored -> new Status(true));
        int failures = ++status.consecutiveFailures;
        if (failures < FAILURE_THRESHOLD) {
            return;
        }
        if (status.alive) {
            logger.warn("Сервер {} не отвечает ({} неудач подряд, последняя причина: {})",
                    name, failures, reason);
        }
        status.alive = false;
    }

    /** Считает сервер живым немедленно — после подтверждённого запуска control-plane. */
    public void assumeUp(@NotNull String serverName) {
        markUp(serverName);
    }

    /** Считает сервер выключенным немедленно — после подтверждённой остановки. */
    public void assumeDown(@NotNull String serverName) {
        Status status = statuses.computeIfAbsent(serverName, ignored -> new Status(false));
        status.alive = false;
        status.consecutiveFailures = FAILURE_THRESHOLD;
    }
}
