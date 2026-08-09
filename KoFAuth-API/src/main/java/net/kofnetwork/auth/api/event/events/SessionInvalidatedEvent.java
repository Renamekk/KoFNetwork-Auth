package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.event.AuthEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Одна или несколько сессий аккаунта перестали действовать.
 *
 * <p>Прокси, получив это событие, возвращает игрока в Limbo, если среди отозванных
 * оказалась его текущая игровая сессия.
 *
 * <p><b>Охват задаётся явно.</b> Раньше он выводился из размера списка: пустой список
 * означал «все сессии аккаунта». Из-за этого отзыв, которому нечего было отзывать,
 * становился неотличим от «выйти со всех устройств». Ровно так ломался обычный вход:
 * {@code revokeAll(accountId, exceptCurrentSession)} для игрока без прежних сессий
 * возвращал пустой список, событие уходило как «все», и прокси выбрасывал того, кто
 * секунду назад ввёл верный пароль.
 *
 * <p>Поэтому охват — отдельное поле из трёх значений:
 * <ul>
 *   <li>{@link Scope#NONE} — не затронута ни одна сессия. Такое событие публиковать
 *       незачем, и {@link #isNoop()} позволяет отсечь его до отправки;</li>
 *   <li>{@link Scope#SOME} — затронуты перечисленные сессии, и только они;</li>
 *   <li>{@link Scope#ALL} — затронуты все сессии аккаунта, включая те, о которых
 *       отправитель не знал.</li>
 * </ul>
 *
 * @param sessionPublicIds перечень затронутых сессий; осмысленно только при
 *                         {@link Scope#SOME}
 */
public record SessionInvalidatedEvent(
        long accountIdValue,
        @NotNull Scope scope,
        @NotNull List<String> sessionPublicIds,
        @NotNull String reason,
        @NotNull Instant occurredAt
) implements AuthEvent {

    /** Насколько широк отзыв. */
    public enum Scope {

        /** Ни одна сессия не затронута. */
        NONE,

        /** Затронуты ровно перечисленные сессии. */
        SOME,

        /** Затронуты все сессии аккаунта. */
        ALL;

        /**
         * Разбирает охват из строкового атрибута удалённого события.
         *
         * <p>Неизвестное значение — это событие от узла другой версии. Ответом
         * служит {@link #NONE}: лишний отзыв выбрасывает играющих людей, а
         * пропущенный исправляется ближайшей сверкой состояния.
         */
        public static @NotNull Scope parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return NONE;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }
    }

    public SessionInvalidatedEvent {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");

        List<String> ids = sessionPublicIds == null ? List.of() : List.copyOf(sessionPublicIds);
        // Перечень имеет смысл только для SOME. Хранить его при ALL значило бы
        // допустить событие, у которого охват и содержимое противоречат друг другу.
        sessionPublicIds = scope == Scope.SOME ? ids : List.of();
        if (scope == Scope.SOME && sessionPublicIds.isEmpty()) {
            // «Затронуты именно эти сессии» при пустом перечне — это NONE.
            // Приводим к нему здесь, а не оставляем вызывающему шанс ошибиться.
            scope = Scope.NONE;
        }
    }

    public static @NotNull SessionInvalidatedEvent single(long accountId,
                                                          @NotNull String sessionPublicId,
                                                          @NotNull String reason) {
        return new SessionInvalidatedEvent(accountId, Scope.SOME, List.of(sessionPublicId),
                reason, Instant.now());
    }

    /**
     * Затронуты перечисленные сессии.
     *
     * <p>Пустой перечень нормализуется в {@link Scope#NONE} — см. компактный конструктор.
     */
    public static @NotNull SessionInvalidatedEvent some(long accountId,
                                                        @NotNull List<String> sessionPublicIds,
                                                        @NotNull String reason) {
        return new SessionInvalidatedEvent(accountId, Scope.SOME, sessionPublicIds,
                reason, Instant.now());
    }

    public static @NotNull SessionInvalidatedEvent all(long accountId, @NotNull String reason) {
        return new SessionInvalidatedEvent(accountId, Scope.ALL, List.of(), reason, Instant.now());
    }

    /** Отзыв, которому нечего было отзывать. */
    public static @NotNull SessionInvalidatedEvent none(long accountId, @NotNull String reason) {
        return new SessionInvalidatedEvent(accountId, Scope.NONE, List.of(), reason, Instant.now());
    }

    @Override
    public @Nullable Long accountId() {
        return accountIdValue;
    }

    /** Затронуты ли все сессии аккаунта. */
    public boolean affectsAll() {
        return scope == Scope.ALL;
    }

    /**
     * Не затронута ни одна сессия.
     *
     * <p>Публиковать такое событие не нужно: подписчики на него не реагируют,
     * а трафик и разбор оно создаёт.
     */
    public boolean isNoop() {
        return scope == Scope.NONE;
    }

    /** Затронута ли конкретная сессия. */
    public boolean affects(@NotNull String publicId) {
        return switch (scope) {
            case ALL -> true;
            case SOME -> sessionPublicIds.contains(publicId);
            case NONE -> false;
        };
    }
}
