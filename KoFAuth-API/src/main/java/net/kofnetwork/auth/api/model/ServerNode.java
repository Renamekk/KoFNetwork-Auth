package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Сервер сети. Соответствует строке {@code servers}.
 *
 * <p>Реестр нужен прежде всего для выбора Limbo: прокси должен знать, какие
 * Limbo-инстансы живы и насколько загружены, чтобы не отправить игрока на упавший.
 *
 * @param priority меньшее значение выбирается первым при прочих равных
 */
public record ServerNode(
        int id,
        @NotNull String name,
        @NotNull ServerType type,
        @NotNull String address,
        int port,
        @Nullable String motd,
        boolean online,
        int playerCount,
        int maxPlayers,
        int priority,
        @Nullable Instant lastHeartbeatAt,
        @NotNull Instant registeredAt
) {

    /** Сервер считается недоступным, если heartbeat не приходил дольше этого срока. */
    public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(30);

    public ServerNode {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(registeredAt, "registeredAt");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Порт должен быть в диапазоне 1..65535, получено " + port);
        }
    }

    /**
     * Готов ли сервер принимать игроков.
     *
     * <p>Флага {@code online} недостаточно: он выставляется самим сервером и остаётся
     * поднятым, если процесс умер, не успев его снять. Поэтому проверяется и свежесть
     * heartbeat.
     */
    public boolean isAvailable(@NotNull Instant at) {
        if (!online) {
            return false;
        }
        if (lastHeartbeatAt == null) {
            return false;
        }
        return Duration.between(lastHeartbeatAt, at).compareTo(HEARTBEAT_TIMEOUT) < 0;
    }

    /** Есть ли свободные слоты. {@code maxPlayers == 0} трактуется как «без ограничения». */
    public boolean hasCapacity() {
        return maxPlayers <= 0 || playerCount < maxPlayers;
    }

    /** Заполненность в долях единицы. Для сервера без ограничения — {@code 0}. */
    public double load() {
        return maxPlayers <= 0 ? 0.0 : (double) playerCount / maxPlayers;
    }

    /** Фиксирует heartbeat с актуальной загрузкой. */
    public @NotNull ServerNode heartbeat(@NotNull Instant at, int players, int max) {
        return new ServerNode(id, name, type, address, port, motd, true, players, max,
                priority, at, registeredAt);
    }

    /** Помечает сервер выключенным. */
    public @NotNull ServerNode offline() {
        return new ServerNode(id, name, type, address, port, motd, false, 0, maxPlayers,
                priority, lastHeartbeatAt, registeredAt);
    }
}
