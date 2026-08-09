package net.kofnetwork.auth.velocity.listener;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Что происходит с входом каждого подключённого игрока.
 *
 * <p><b>Зачем понадобился отдельный учёт.</b> Отключение по таймауту раньше опиралось на
 * две независимые величины: момент подключения и состояние в Redis. Между чтением
 * состояния и вызовом {@code disconnect()} проходило время — цепочка асинхронная, — и в
 * этот промежуток помещался успешный вход. Игрок вводил верный пароль, получал сессию,
 * начинал переходить на хаб и в этот самый момент отключался с сообщением «Вы не успели
 * войти». Со стороны это выглядело как случайный кик после правильного пароля.
 *
 * <p>Здесь момент, с которого игрок перестаёт быть кандидатом на отключение, фиксируется
 * <em>до</em> начала перевода и в том же процессе, что и решение об отключении. Проверка
 * идёт по одной величине, а не по двум, которые могут разойтись.
 *
 * <p>Второе назначение — подтверждение через мессенджер. Ожидание кнопки длится дольше
 * обычного ввода пароля, и общий таймаут его бы обрывал. Ожидание отмечается явно, со
 * своим сроком, и по идентификатору попытки решение владельца сопоставляется с той
 * попыткой, которая его ждёт: повторный {@code /login} начинает новую, и решение по
 * прежней уже никого не пускает.
 */
public final class PendingLogins {

    /** Состояние входа одного игрока. */
    private record Attempt(@NotNull String attemptId,
                           @NotNull Instant startedAt,
                           @Nullable Instant approvalDeadline,
                           boolean completed) {
    }

    private final Map<UUID, Attempt> attempts = new ConcurrentHashMap<>();

    /** Момент подключения — точка отсчёта таймаута входа. */
    private final Map<UUID, Instant> connectedAt = new ConcurrentHashMap<>();

    public void connected(@NotNull UUID playerUuid) {
        connectedAt.put(playerUuid, Instant.now());
        attempts.remove(playerUuid);
    }

    public void disconnected(@NotNull UUID playerUuid) {
        connectedAt.remove(playerUuid);
        attempts.remove(playerUuid);
    }

    public @NotNull Instant connectedAt(@NotNull UUID playerUuid) {
        return connectedAt.getOrDefault(playerUuid, Instant.now());
    }

    public boolean isKnown(@NotNull UUID playerUuid) {
        return connectedAt.containsKey(playerUuid);
    }

    /**
     * Отмечает игрока как ожидающего подтверждения в мессенджере.
     *
     * @param attemptId метка попытки из {@code AuthResult.attemptId()}
     */
    public void awaitingApproval(@NotNull UUID playerUuid,
                                 @NotNull String attemptId,
                                 @NotNull Duration window) {
        attempts.put(playerUuid, new Attempt(attemptId, Instant.now(),
                Instant.now().plus(window), false));
    }

    /**
     * Отмечает вход завершённым.
     *
     * <p>Вызывается до начала перевода на хаб, а не после: перевод занимает время, и
     * именно в нём игрок раньше успевал попасть под отключение по таймауту.
     */
    public void completed(@NotNull UUID playerUuid) {
        attempts.compute(playerUuid, (ignored, existing) -> existing == null
                ? new Attempt("", Instant.now(), null, true)
                : new Attempt(existing.attemptId(), existing.startedAt(),
                        existing.approvalDeadline(), true));
    }

    /** Сбрасывает отметку: игрок снова обязан войти. */
    public void reset(@NotNull UUID playerUuid) {
        attempts.remove(playerUuid);
    }

    /**
     * Освобождён ли игрок от отключения по таймауту.
     *
     * <p>Освобождены двое: тот, чей вход уже состоялся, и тот, кто ждёт решения
     * владельца, — пока не истёк отдельный срок этого ожидания.
     */
    public boolean isExemptFromTimeout(@NotNull UUID playerUuid, @NotNull Instant now) {
        Attempt attempt = attempts.get(playerUuid);
        if (attempt == null) {
            return false;
        }
        if (attempt.completed()) {
            return true;
        }
        return attempt.approvalDeadline() != null && now.isBefore(attempt.approvalDeadline());
    }

    /** Метка попытки, которую ведёт этот игрок. */
    public @NotNull Optional<String> attemptId(@NotNull UUID playerUuid) {
        Attempt attempt = attempts.get(playerUuid);
        return attempt == null || attempt.attemptId().isEmpty()
                ? Optional.empty()
                : Optional.of(attempt.attemptId());
    }

    /**
     * Относится ли решение к попытке, которую ведёт этот игрок.
     *
     * <p>Повторный {@code /login} начинает новую попытку, и решение по прежней обязано
     * быть отброшено: иначе нажатие старой кнопки завершало бы вход, от которого игрок
     * уже отказался.
     */
    public boolean matchesAttempt(@NotNull UUID playerUuid, @NotNull String attemptId) {
        return attemptId(playerUuid).map(attemptId::equals).orElse(false);
    }
}
