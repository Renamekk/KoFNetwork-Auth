package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.SessionDto;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.api.result.OperationResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Управление сессиями и состоянием машины входа.
 *
 * <p>Сессия живёт в двух местах: горячая копия в Redis отвечает на «пускать ли сейчас»,
 * запись в MySQL — на «покажи мои сессии». Этот сервис держит их согласованными.
 */
public interface SessionService {

    /** Создаёт сессию после успешной аутентификации. */
    @NotNull CompletableFuture<Session> create(long accountId,
                                               @NotNull SessionType type,
                                               @NotNull AuthContext context,
                                               @Nullable Long deviceId);

    /**
     * Проверяет действительность сессии игрока.
     *
     * <p>Самая частая операция системы: выполняется при каждом переключении сервера и
     * при переподключении. Читает Redis; в MySQL уходит только при промахе кэша.
     *
     * @param currentIp текущий адрес; при включённой привязке к IP несовпадение
     *                  отзывает сессию и порождает событие подозрительной активности
     */
    @NotNull CompletableFuture<Optional<Session>> validate(@NotNull UUID playerUuid, @NotNull IpAddress currentIp);

    /** Проверяет сессию по внешнему идентификатору — путь веб-API и ботов. */
    @NotNull CompletableFuture<Optional<Session>> validateByPublicId(@NotNull String publicId,
                                                                     @NotNull IpAddress currentIp);

    /**
     * Связывает UUID игрока с игровой сессией в кэше.
     *
     * <p>Нужно только для Minecraft: веб-сессия опознаётся по токену, а игрок —
     * по UUID, который приходит в каждом подключении. Без этой связки
     * {@link #validate(UUID, IpAddress)} не сможет найти сессию, и игрок будет
     * вводить пароль при каждом переподключении.
     */
    @NotNull CompletableFuture<Void> cacheForPlayer(@NotNull UUID playerUuid,
                                                    @NotNull Session session);

    /**
     * Продлевает сессию активностью.
     *
     * <p>Реализация обязана ограничивать частоту записи: обновлять {@code last_seen_at}
     * в MySQL на каждом пакете от игрока — это тысячи UPDATE в секунду ради поля,
     * точность которого никому не нужна. Redis обновляется сразу, база — не чаще
     * раза в минуту на сессию.
     */
    @NotNull CompletableFuture<Void> touch(@NotNull String publicId, @NotNull AuthContext context);

    /** Фиксирует переход игрока на другой сервер сети. */
    @NotNull CompletableFuture<Void> updateServer(@NotNull String publicId, @NotNull String server);

    /** Завершает сессию. */
    @NotNull CompletableFuture<OperationResult<Void>> revoke(@NotNull String publicId, @NotNull String reason);

    /**
     * Завершает все сессии аккаунта.
     *
     * @param exceptPublicId сессия, которую нужно сохранить; {@code null} — завершить все
     * @return число завершённых сессий
     */
    @NotNull CompletableFuture<Integer> revokeAll(long accountId,
                                                  @Nullable String exceptPublicId,
                                                  @NotNull String reason);

    /** Сессии аккаунта для личного кабинета. */
    @NotNull CompletableFuture<List<SessionDto>> listSessions(long accountId, @Nullable String currentPublicId);

    // ------------------------------------------------------------------ состояние машины входа

    /**
     * Текущее состояние игрока в процессе входа.
     *
     * <p>Хранится в Redis, а не в памяти процесса: игрок может быть переброшен между
     * Limbo-инстансами, и локальное состояние в этот момент потерялось бы вместе с
     * прогрессом прохождения CAPTCHA.
     */
    @NotNull CompletableFuture<AuthState> getState(@NotNull UUID playerUuid);

    /**
     * Переводит игрока в новое состояние.
     *
     * @return {@code false}, если переход недопустим
     * @see AuthState#canTransitionTo(AuthState)
     */
    @NotNull CompletableFuture<Boolean> setState(@NotNull UUID playerUuid, @NotNull AuthState state);

    /** Сбрасывает состояние при отключении игрока. */
    @NotNull CompletableFuture<Void> clearState(@NotNull UUID playerUuid);
}
