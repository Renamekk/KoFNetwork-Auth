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
     * Идентификатор сессии, привязанной к UUID, без проверки её действительности.
     *
     * <p>Отличается от {@link #validate(UUID, IpAddress)} тем, что ничего не меняет:
     * не ходит в базу, не отзывает сессию при смене адреса и не порождает событий.
     * Нужен там, где вопрос стоит «какая сессия у этого игрока сейчас», а не
     * «пускать ли его»: например, при разборе события об отзыве сессий, где
     * {@code validate} на каждом игроке сети означал бы запрос в MySQL на каждого
     * и лавину событий о несовпадении адреса.
     */
    @NotNull CompletableFuture<Optional<String>> currentPublicId(@NotNull UUID playerUuid);

    /**
     * Действует ли сейчас сессия игрока.
     *
     * <p>Проверка без побочных эффектов: срок и признак отзыва сверяются, адрес —
     * нет. Предназначена для бэкенда, где {@link #validate(UUID, IpAddress)}
     * применять опасно. Paper видит адрес игрока не всегда — на раннем этапе входа
     * {@code Player#getAddress()} может вернуть {@code null}, — а
     * {@link IpAddress#ofNullable} превращает это в {@link IpAddress#UNKNOWN},
     * который не совпадёт с адресом сессии. Для {@code validate} такое
     * несовпадение означает угон: он отзывает действующую сессию и публикует
     * событие, то есть проверка доступа уничтожает то, что проверяет.
     *
     * <p>Отсутствие сверки адреса здесь не ослабляет защиту: привязку UUID
     * к сессии выставляет только успешный вход, а доступ в сеть в любом случае
     * закрыт прокси.
     */
    @NotNull CompletableFuture<Boolean> hasValidSession(@NotNull UUID playerUuid);

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

    /**
     * Продлевает сессию игрока вместе с привязкой к его UUID.
     *
     * <p>Игровой слой должен вызывать именно этот метод, а не
     * {@link #touch(String, AuthContext)}. Продления одной лишь сессии
     * недостаточно: привязка {@code UUID → сессия} живёт в кэше со своим сроком,
     * равным скользящему сроку сессии, и если продлевать только сессию, привязка
     * всё равно исчезнет — сессия останется живой, но найти её по UUID станет
     * нельзя, и игрок будет вводить пароль при следующем подключении.
     *
     * <p>Ничего не делает, если привязки нет: игрок не вошёл.
     */
    @NotNull CompletableFuture<Void> touchPlayer(@NotNull UUID playerUuid, @NotNull AuthContext context);

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

    /**
     * Задаёт состояние в обход проверки переходов.
     *
     * <p>Вызывается ровно в одном месте — в начале нового подключения, когда
     * прокси определил, что требуется от игрока. Новое соединение начинает машину
     * входа заново, и остаток прошлой её не касается.
     *
     * <p><b>Почему нельзя обойтись {@link #setState(UUID, AuthState)}.</b>
     * {@link AuthState#AUTHENTICATED} и {@link AuthState#BLOCKED} — терминальные:
     * выхода из них нет. Запись о прошлом входе живёт в Redis дольше самого
     * соединения (разрыв связи не всегда доходит до прокси, а прокси может быть
     * перезапущен), и тогда переход «был вошёл → теперь нужен пароль» отвергался,
     * состояние оставалось {@code AUTHENTICATED}, и игрок с истёкшей сессией
     * уезжал мимо Limbo прямо на игровой сервер — где его отключал уже бэкенд,
     * не нашедший сессии.
     */
    @NotNull CompletableFuture<Void> resetState(@NotNull UUID playerUuid, @NotNull AuthState state);

    /** Сбрасывает состояние при отключении игрока. */
    @NotNull CompletableFuture<Void> clearState(@NotNull UUID playerUuid);
}
