package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.SessionDto;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.AccountLogoutEvent;
import net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent;
import net.kofnetwork.auth.api.event.events.SuspiciousActivityEvent;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.api.repository.DeviceRepository;
import net.kofnetwork.auth.api.repository.SessionRepository;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.api.service.SessionService;
import net.kofnetwork.auth.core.cache.CacheProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реализация {@link SessionService}.
 *
 * <p><b>Два хранилища.</b> Redis отвечает на вопрос «пускать ли прямо сейчас» —
 * этот вопрос задаётся на каждом переключении сервера. MySQL отвечает на «покажи
 * мои сессии» в личном кабинете. Сервис держит их согласованными: запись идёт в оба,
 * чтение — сначала в Redis, при промахе в MySQL с прогревом кэша.
 */
public final class SessionServiceImpl implements SessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionServiceImpl.class);

    /** Поля хэша сессии в Redis. */
    private static final String FIELD_ACCOUNT_ID = "accountId";
    private static final String FIELD_PUBLIC_ID = "publicId";
    private static final String FIELD_IP = "ip";
    private static final String FIELD_SESSION_DB_ID = "sessionId";

    /**
     * Как часто {@code last_seen_at} переписывается в MySQL.
     *
     * <p>Обновлять его на каждом пакете от игрока — это тысячи {@code UPDATE} в
     * секунду ради поля, точность которого никому не нужна. Redis обновляется сразу,
     * база — не чаще раза в минуту на сессию.
     */
    private static final Duration DB_TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final SessionRepository sessions;
    private final DeviceRepository devices;
    private final CacheProvider cache;
    private final ConfigurationService config;
    private final EventBus events;

    /** Когда сессия последний раз записывалась в базу. Ключ — публичный идентификатор. */
    private final Map<String, Instant> lastDbTouch = new ConcurrentHashMap<>();

    public SessionServiceImpl(@NotNull SessionRepository sessions,
                              @NotNull DeviceRepository devices,
                              @NotNull CacheProvider cache,
                              @NotNull ConfigurationService config,
                              @NotNull EventBus events) {
        this.sessions = sessions;
        this.devices = devices;
        this.cache = cache;
        this.config = config;
        this.events = events;
    }

    private Duration slidingTtl() {
        return config.getDuration(ConfigFile.CONFIG, "auth.session.ttl", Duration.ofHours(24));
    }

    private Duration absoluteTtl() {
        return config.getDuration(ConfigFile.CONFIG, "auth.session.absolute-ttl", Duration.ofDays(7));
    }

    private boolean bindsToIp(SessionType type) {
        return config.getBoolean(ConfigFile.CONFIG, "auth.session.bind-to-ip",
                type.bindsToIpByDefault());
    }

    @Override
    public @NotNull CompletableFuture<Session> create(long accountId,
                                                      @NotNull SessionType type,
                                                      @NotNull AuthContext context,
                                                      @Nullable Long deviceId) {
        Session session = Session.create(accountId, deviceId, type, context.ip(),
                        context.userAgent(), slidingTtl(), absoluteTtl())
                .withGeo(context.country(), context.city())
                .withServer(context.server());

        return sessions.insert(session).thenCompose(saved -> cacheSession(saved).thenApply(ignored -> saved));
    }

    /** Кладёт горячую копию сессии в Redis. */
    private CompletableFuture<Void> cacheSession(Session session) {
        // Ключ по UUID игрока недоступен: сессия знает только accountId. Для игры
        // ключом служит публичный идентификатор, а связка uuid → сессия
        // устанавливается платформенным модулем через cacheForPlayer.
        return cache.setHash(CacheProvider.Keys.SESSION + session.publicId(),
                Map.of(FIELD_ACCOUNT_ID, String.valueOf(session.accountId()),
                        FIELD_PUBLIC_ID, session.publicId(),
                        FIELD_IP, session.ip().asString(),
                        FIELD_SESSION_DB_ID, String.valueOf(session.id())),
                slidingTtl());
    }

    @Override
    public @NotNull CompletableFuture<Void> cacheForPlayer(@NotNull UUID playerUuid,
                                                           @NotNull Session session) {
        return cache.set(CacheProvider.Keys.SESSION + playerUuid, session.publicId(), slidingTtl());
    }

    @Override
    public @NotNull CompletableFuture<Optional<Session>> validate(@NotNull UUID playerUuid,
                                                                  @NotNull IpAddress currentIp) {
        return cache.get(CacheProvider.Keys.SESSION + playerUuid)
                .thenCompose(publicId -> publicId
                        .map(id -> validateByPublicId(id, currentIp))
                        .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())));
    }

    @Override
    public @NotNull CompletableFuture<Optional<Session>> validateByPublicId(@NotNull String publicId,
                                                                            @NotNull IpAddress currentIp) {
        return sessions.findByPublicId(publicId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<Session>empty());
            }
            Session session = found.get();
            Instant now = Instant.now();

            if (!session.isValid(now)) {
                return CompletableFuture.completedFuture(Optional.<Session>empty());
            }

            if (bindsToIp(session.type()) && !session.matchesIp(currentIp)) {
                // Смена адреса у привязанной сессии — либо угон, либо смена сети.
                // Различить нельзя, поэтому сессия отзывается, а событие уходит
                // владельцу: пусть решает он.
                return revokeOnIpMismatch(session, currentIp);
            }

            return CompletableFuture.completedFuture(Optional.of(session));
        });
    }

    private CompletableFuture<Optional<Session>> revokeOnIpMismatch(Session session,
                                                                     IpAddress currentIp) {
        LOGGER.warn("Сессия {} отозвана: адрес изменился с {} на {}",
                session.publicId(), session.ip().asMasked(), currentIp.asMasked());

        return revoke(session.publicId(), Session.REASON_IP_MISMATCH)
                .thenCompose(ignored -> events.publish(SuspiciousActivityEvent.of(
                        session.accountId(),
                        SecurityEventType.SESSION_IP_MISMATCH,
                        AuthContext.minecraft(currentIp, session.server(), null, null),
                        "Адрес сессии изменился с " + session.ip().asMasked())))
                .thenApply(ignored -> Optional.empty());
    }

    @Override
    public @NotNull CompletableFuture<Void> touch(@NotNull String publicId,
                                                  @NotNull AuthContext context) {
        Instant now = Instant.now();
        Duration ttl = slidingTtl();

        // Redis продлевается всегда: это дешёвая операция и именно она отвечает
        // на «пускать ли сейчас».
        CompletableFuture<Void> cacheUpdate = cache.getHash(CacheProvider.Keys.SESSION + publicId)
                .thenCompose(hash -> hash.isEmpty()
                        ? CompletableFuture.completedFuture(null)
                        : cache.setHash(CacheProvider.Keys.SESSION + publicId, hash, ttl));

        Instant previous = lastDbTouch.get(publicId);
        if (previous != null && Duration.between(previous, now).compareTo(DB_TOUCH_INTERVAL) < 0) {
            return cacheUpdate;
        }
        lastDbTouch.put(publicId, now);

        return cacheUpdate.thenCompose(ignored ->
                sessions.findByPublicId(publicId).thenCompose(found -> found
                        .map(session -> sessions.touch(session.id(), now, now.plus(ttl)))
                        .orElseGet(() -> CompletableFuture.completedFuture(null))));
    }

    @Override
    public @NotNull CompletableFuture<Void> updateServer(@NotNull String publicId,
                                                         @NotNull String server) {
        return sessions.findByPublicId(publicId).thenCompose(found -> found
                .map(session -> sessions.updateServer(session.id(), server))
                .orElseGet(() -> CompletableFuture.completedFuture(null)));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> revoke(@NotNull String publicId,
                                                                     @NotNull String reason) {
        return sessions.findByPublicId(publicId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(
                        OperationResult.<Void>fail("SESSION_NOT_FOUND", "Сессия не найдена"));
            }
            Session session = found.get();
            return sessions.revoke(publicId, Instant.now(), reason)
                    .thenCompose(ignored -> cache.delete(CacheProvider.Keys.SESSION + publicId))
                    .thenCompose(ignored -> {
                        lastDbTouch.remove(publicId);
                        return events.publish(AccountLogoutEvent.of(
                                session.accountId(), publicId, reason));
                    })
                    .thenApply(ignored -> OperationResult.<Void>ok());
        });
    }

    @Override
    public @NotNull CompletableFuture<Integer> revokeAll(long accountId,
                                                          @Nullable String exceptPublicId,
                                                          @NotNull String reason) {
        Instant now = Instant.now();
        return sessions.findActiveByAccount(accountId, now).thenCompose(active -> {
            List<String> affected = active.stream()
                    .map(Session::publicId)
                    .filter(id -> !id.equals(exceptPublicId))
                    .toList();

            return sessions.revokeAllForAccount(accountId, exceptPublicId, now, reason)
                    .thenCompose(count -> {
                        // Горячие копии убираем поимённо: deleteByPattern прошёлся бы
                        // по всем ключам Redis, что на большой сети недопустимо дорого.
                        CompletableFuture<?>[] deletions = affected.stream()
                                .peek(lastDbTouch::remove)
                                .map(id -> cache.delete(CacheProvider.Keys.SESSION + id))
                                .toArray(CompletableFuture[]::new);
                        return CompletableFuture.allOf(deletions).thenApply(ignored -> count);
                    })
                    .thenCompose(count -> events
                            .publish(exceptPublicId == null
                                    ? SessionInvalidatedEvent.all(accountId, reason)
                                    : new SessionInvalidatedEvent(accountId, affected, reason, now))
                            .thenApply(ignored -> count));
        });
    }

    @Override
    public @NotNull CompletableFuture<List<SessionDto>> listSessions(long accountId,
                                                                      @Nullable String currentPublicId) {
        Instant now = Instant.now();
        return sessions.findActiveByAccount(accountId, now).thenCompose(active -> {
            if (active.isEmpty()) {
                return CompletableFuture.completedFuture(List.<SessionDto>of());
            }
            // Имена устройств подтягиваются одним запросом, а не по одному на сессию.
            return devices.findByAccount(accountId).thenApply(deviceList -> {
                Map<Long, String> names = new java.util.HashMap<>();
                deviceList.forEach(device -> names.put(device.id(), device.friendlyName()));
                return active.stream()
                        .map(session -> SessionDto.from(session,
                                session.deviceId() == null ? null : names.get(session.deviceId()),
                                session.publicId().equals(currentPublicId)))
                        .toList();
            });
        });
    }

    // ------------------------------------------------------------------ состояние машины входа

    @Override
    public @NotNull CompletableFuture<AuthState> getState(@NotNull UUID playerUuid) {
        return cache.get(CacheProvider.Keys.AUTH_STATE + playerUuid).thenApply(raw -> {
            if (raw.isEmpty()) {
                return AuthState.CONNECTING;
            }
            try {
                return AuthState.valueOf(raw.get());
            } catch (IllegalArgumentException e) {
                return AuthState.CONNECTING;
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<Boolean> setState(@NotNull UUID playerUuid,
                                                         @NotNull AuthState state) {
        return getState(playerUuid).thenCompose(current -> {
            if (!current.canTransitionTo(state)) {
                // Недопустимый переход — признак ошибки в коде или гонки.
                // Логируем, но не бросаем: игрок не должен страдать из-за этого.
                LOGGER.warn("Недопустимый переход состояния игрока {}: {} -> {}",
                        playerUuid, current, state);
                return CompletableFuture.completedFuture(false);
            }
            return cache.set(CacheProvider.Keys.AUTH_STATE + playerUuid, state.name(),
                            Duration.ofMinutes(5))
                    .thenApply(ignored -> true);
        });
    }

    /**
     * Убирает <em>только</em> состояние машины входа.
     *
     * <p>Привязка UUID к сессии намеренно остаётся. Удалять её при отключении
     * нельзя: игрок, у которого оборвалось соединение, должен вернуться без
     * повторного ввода пароля — иначе сессия не выполняет свою единственную задачу.
     * Сессия заканчивается по сроку либо явным {@link #revoke(String, String)}.
     */
    @Override
    public @NotNull CompletableFuture<Void> clearState(@NotNull UUID playerUuid) {
        return cache.delete(CacheProvider.Keys.AUTH_STATE + playerUuid)
                .thenApply(ignored -> null);
    }
}
