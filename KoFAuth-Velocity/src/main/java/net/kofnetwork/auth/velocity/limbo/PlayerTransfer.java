package net.kofnetwork.auth.velocity.limbo;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Перевод игрока между серверами с разбором результата.
 *
 * <p><b>Что здесь исправлено.</b> Прежний код после успешного входа вызывал
 * {@code createConnectionRequest(lobby).fireAndForget()} — то есть отправлял запрос и
 * не смотрел, чем он кончился. Если хаб отвергал подключение (перезапускается, полон,
 * не успел прогреться), игрок оставался стоять в Limbo без единого сообщения: вход
 * состоялся, сессия есть, а он смотрит в пустоту и обычно переподключается вручную.
 *
 * <p>Здесь результат разбирается, и при отказе перебирается следующий хаб. Перебор
 * ограничен списком: пробовать бесконечно нельзя, а сообщить о неудаче — можно и нужно.
 */
public final class PlayerTransfer {

    private final ServerPool pool;
    private final Logger logger;

    public PlayerTransfer(@NotNull ServerPool pool, @NotNull Logger logger) {
        this.pool = pool;
        this.logger = logger;
    }

    /** Исход перевода. */
    public record Outcome(boolean success, @NotNull String server, @NotNull String detail) {

        public static @NotNull Outcome failed(@NotNull String detail) {
            return new Outcome(false, "", detail);
        }
    }

    /**
     * Переводит игрока на хаб, перебирая доступные при отказе.
     *
     * <p>Бронь на время подключения снимается в любом случае — иначе неудачная попытка
     * навсегда завышала бы загрузку хаба и выводила его из выбора.
     */
    public @NotNull CompletableFuture<Outcome> toHub(@NotNull Player player) {
        List<RegisteredServer> hubs = pool.hubsByPreference();
        if (hubs.isEmpty()) {
            logger.error("Нет доступного хаба для игрока {}", player.getUsername());
            return CompletableFuture.completedFuture(
                    Outcome.failed("нет доступного хаба"));
        }
        return attempt(player, hubs, 0);
    }

    private CompletableFuture<Outcome> attempt(Player player, List<RegisteredServer> hubs, int index) {
        if (index >= hubs.size()) {
            logger.error("Игрока {} не принял ни один хаб из {}", player.getUsername(), hubs.size());
            return CompletableFuture.completedFuture(
                    Outcome.failed("ни один хаб не принял подключение"));
        }
        RegisteredServer target = hubs.get(index);
        ServerPool.Reservation reservation = pool.acquire(target);
        String name = target.getServerInfo().getName();

        return player.createConnectionRequest(target).connect()
                .handle((result, failure) -> {
                    reservation.release();
                    if (failure != null) {
                        logger.warn("Подключение игрока {} к хабу {} сорвалось: {}",
                                player.getUsername(), name, failure.toString());
                        return false;
                    }
                    if (!result.isSuccessful()) {
                        logger.warn("Хаб {} отклонил игрока {}: {}", name, player.getUsername(),
                                result.getStatus());
                        return false;
                    }
                    return true;
                })
                .thenCompose(connected -> {
                    if (Boolean.TRUE.equals(connected)) {
                        return CompletableFuture.completedFuture(new Outcome(true, name, "подключён"));
                    }
                    if (!player.isActive()) {
                        // Игрок отключился по дороге: перебирать дальше некого.
                        return CompletableFuture.completedFuture(
                                Outcome.failed("игрок отключился"));
                    }
                    return attempt(player, hubs, index + 1);
                });
    }

    /**
     * Переводит игрока в Limbo.
     *
     * <p>Используется, когда игрок обязан вернуться к аутентификации: сессию отозвали,
     * состояние сброшено. Отказ здесь означает, что аутентифицироваться негде, — и это
     * повод отключить игрока, а не оставить его на боевом сервере без сессии.
     */
    public @NotNull CompletableFuture<Outcome> toLimbo(@NotNull Player player) {
        return pool.reserveLimbo()
                .map(reservation -> player.createConnectionRequest(reservation.server()).connect()
                        .handle((result, failure) -> {
                            reservation.release();
                            boolean ok = failure == null && result != null && result.isSuccessful();
                            if (!ok) {
                                logger.warn("Не удалось вернуть игрока {} в Limbo {}",
                                        player.getUsername(), reservation.name());
                            }
                            return new Outcome(ok, reservation.name(),
                                    ok ? "подключён" : "отказ Limbo");
                        }))
                .orElseGet(() -> CompletableFuture.completedFuture(
                        Outcome.failed("нет доступного Limbo")));
    }

    /** Статус, с которым Velocity отклонил подключение, — для сообщения игроку. */
    public static @NotNull String describe(@NotNull ConnectionRequestBuilder.Status status) {
        return switch (status) {
            case SUCCESS -> "подключено";
            case ALREADY_CONNECTED -> "игрок уже на этом сервере";
            case CONNECTION_IN_PROGRESS -> "подключение уже выполняется";
            case CONNECTION_CANCELLED -> "подключение отменено плагином";
            case SERVER_DISCONNECTED -> "сервер разорвал соединение";
        };
    }
}
