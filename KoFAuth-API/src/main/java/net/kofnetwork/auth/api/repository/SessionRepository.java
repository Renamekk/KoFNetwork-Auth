package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ к таблице {@code sessions}.
 *
 * <p>Здесь только долговременное хранилище. Проверка «действительна ли сессия прямо
 * сейчас» идёт через Redis и в этот интерфейс не входит: она выполняется на каждом
 * пакете от игрока, и обращение к MySQL такой частоты недопустимо.
 * За горячий путь отвечает {@code SessionService}.
 */
public interface SessionRepository {

    @NotNull CompletableFuture<Session> insert(@NotNull Session session);

    @NotNull CompletableFuture<Optional<Session>> findByPublicId(@NotNull String publicId);

    @NotNull CompletableFuture<Optional<Session>> findById(long id);

    /** Действующие сессии аккаунта: не отозванные и не истёкшие. */
    @NotNull CompletableFuture<List<Session>> findActiveByAccount(long accountId, @NotNull Instant at);

    /** Все сессии аккаунта, включая завершённые, от новых к старым. */
    @NotNull CompletableFuture<List<Session>> findByAccount(long accountId, int limit, int offset);

    /** Действующие сессии аккаунта указанного канала. */
    @NotNull CompletableFuture<List<Session>> findActiveByAccountAndType(long accountId,
                                                                        @NotNull SessionType type,
                                                                        @NotNull Instant at);

    /** Продлевает сессию активностью. */
    @NotNull CompletableFuture<Void> touch(long sessionId, @NotNull Instant lastSeenAt, @NotNull Instant expiresAt);

    /** Фиксирует переход игрока на другой сервер сети. */
    @NotNull CompletableFuture<Void> updateServer(long sessionId, @NotNull String server);

    /** Отзывает одну сессию. */
    @NotNull CompletableFuture<Boolean> revoke(@NotNull String publicId,
                                               @NotNull Instant at,
                                               @NotNull String reason);

    /**
     * Отзывает все сессии аккаунта.
     *
     * @param exceptPublicId сессия, которую нужно сохранить ({@code null} — отозвать все).
     *                       Нужно для «выйти со всех устройств, кроме текущего»: без
     *                       исключения игрок выкидывает сам себя тем же действием,
     *                       которым защищается
     * @return число отозванных сессий
     */
    @NotNull CompletableFuture<Integer> revokeAllForAccount(long accountId,
                                                            @org.jetbrains.annotations.Nullable String exceptPublicId,
                                                            @NotNull Instant at,
                                                            @NotNull String reason);

    /**
     * Помечает истёкшие сессии отозванными. Вызывается планировщиком.
     *
     * @return число обработанных записей
     */
    @NotNull CompletableFuture<Integer> revokeExpired(@NotNull Instant at);

    /** Удаляет завершённые сессии старше указанного момента. */
    @NotNull CompletableFuture<Integer> deleteRevokedBefore(@NotNull Instant before);

    /** Число действующих сессий аккаунта. */
    @NotNull CompletableFuture<Integer> countActive(long accountId, @NotNull Instant at);
}
