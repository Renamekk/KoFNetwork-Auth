package net.kofnetwork.auth.core.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.AccountLoginEvent;
import net.kofnetwork.auth.api.event.events.LoginApprovalRequestedEvent;
import net.kofnetwork.auth.api.event.events.BindingChangedEvent;
import net.kofnetwork.auth.api.event.events.PasswordChangedEvent;
import net.kofnetwork.auth.api.event.events.RemoteEvent;
import net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent;
import net.kofnetwork.auth.api.event.events.SuspiciousActivityEvent;
import net.kofnetwork.auth.core.cache.CacheProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Межпроцессная доставка событий через Redis Pub/Sub.
 *
 * <p>Надстройка над {@link SimpleEventBus}: локальная раздача остаётся за ней, а
 * этот класс добавляет отправку событий с {@link AuthEvent#isDistributed()} в общий
 * канал и приём чужих.
 *
 * <p><b>Что уезжает по сети.</b> Не сам объект события, а {@link RemoteEvent} —
 * плоский набор скалярных атрибутов. Причина в том, что доменные события несут
 * внутри {@code Account} с хэшем пароля, и сериализация «как есть» разложила бы
 * хэши всей сети по каналу Pub/Sub. Здесь для каждого типа события явно перечислено,
 * какие поля безопасно передавать: поля набираются белым списком, а не исключаются
 * чёрным, поэтому забыть вычеркнуть секрет попросту негде.
 *
 * <p><b>Свои события отбрасываются</b> по {@code nodeId}: узел уже раздал их
 * локальным подписчикам в момент публикации, и повторная обработка удвоила бы
 * все реакции.
 */
public final class RedisEventBridge implements EventBus, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisEventBridge.class);

    private final SimpleEventBus local;
    private final CacheProvider cache;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String nodeId;

    private CacheProvider.Subscription subscription;

    /**
     * @param local локальная шина, которой достаётся раздача подписчикам
     * @param cache источник Pub/Sub; при {@link CacheProvider#isAvailable()} равном
     *              {@code false} мост вырождается в локальную шину
     */
    public RedisEventBridge(@NotNull SimpleEventBus local, @NotNull CacheProvider cache) {
        this(local, cache, UUID.randomUUID().toString());
    }

    RedisEventBridge(@NotNull SimpleEventBus local, @NotNull CacheProvider cache, @NotNull String nodeId) {
        this.local = local;
        this.cache = cache;
        this.nodeId = nodeId;
    }

    /** Подписывается на общий канал. Без вызова мост только отправляет, но не принимает. */
    public void start() {
        if (!cache.isAvailable()) {
            LOGGER.info("Redis недоступен: события остаются в пределах процесса");
            return;
        }
        subscription = cache.subscribe(CacheProvider.Keys.CHANNEL_EVENTS, this::onRemoteMessage);
        LOGGER.info("Межпроцессная шина событий запущена, идентификатор узла {}", nodeId);
    }

    private void onRemoteMessage(String payload) {
        try {
            Map<String, String> envelope = mapper.readValue(payload, new TypeReference<>() {
            });

            String senderNode = envelope.get("node");
            if (nodeId.equals(senderNode)) {
                // Собственное событие, уже роздано локально в момент публикации.
                return;
            }

            String type = envelope.get("type");
            if (type == null) {
                LOGGER.warn("Получено событие без типа, пропущено");
                return;
            }

            Map<String, String> attributes = new LinkedHashMap<>(envelope);
            attributes.remove("node");
            attributes.remove("type");
            attributes.remove("accountId");
            attributes.remove("occurredAt");

            Long accountId = parseLong(envelope.get("accountId"));
            Instant occurredAt = parseInstant(envelope.get("occurredAt"));

            local.dispatch(new RemoteEvent(type, senderNode == null ? "unknown" : senderNode,
                    accountId, attributes, occurredAt));
        } catch (Exception e) {
            // Чужой узел мог отправить сообщение несовместимой версии. Это не повод
            // ронять подписчика: остальные сообщения должны продолжать приходить.
            LOGGER.warn("Не удалось разобрать событие из Redis: {}", e.getMessage());
        }
    }

    private static Long parseLong(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseInstant(String raw) {
        if (raw == null) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    @Override
    public @NotNull CompletableFuture<Void> publish(@NotNull AuthEvent event) {
        CompletableFuture<Void> localDelivery = local.publish(event);
        if (event.isDistributed() && cache.isAvailable()) {
            // Отправку в Redis не ждём: локальные подписчики важнее, а доставка
            // на соседние узлы допускает задержку.
            broadcast(event);
        }
        return localDelivery;
    }

    @Override
    public @NotNull CompletableFuture<Void> publishAndAwait(@NotNull AuthEvent event) {
        CompletableFuture<Void> localDelivery = local.publishAndAwait(event);
        if (event.isDistributed() && cache.isAvailable()) {
            return localDelivery.thenCompose(ignored -> broadcast(event));
        }
        return localDelivery;
    }

    private CompletableFuture<Void> broadcast(AuthEvent event) {
        Map<String, String> envelope = describe(event);
        if (envelope == null) {
            // Тип не описан как передаваемый: отправлять «на всякий случай»
            // сериализованный объект нельзя — именно так секреты и утекают.
            LOGGER.debug("Событие {} помечено как распределённое, но не имеет описания "
                    + "для передачи по сети — доставлено только локально",
                    event.getClass().getSimpleName());
            return CompletableFuture.completedFuture(null);
        }
        envelope.put("type", event.getClass().getSimpleName());
        envelope.put("node", nodeId);
        envelope.put("occurredAt", event.occurredAt().toString());
        if (event.accountId() != null) {
            envelope.put("accountId", String.valueOf(event.accountId()));
        }
        try {
            String json = mapper.writeValueAsString(envelope);
            return cache.publish(CacheProvider.Keys.CHANNEL_EVENTS, json).thenApply(ignored -> null);
        } catch (Exception e) {
            LOGGER.warn("Не удалось отправить событие {} в Redis: {}",
                    event.getClass().getSimpleName(), e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Белый список полей, безопасных для передачи по сети.
     *
     * <p>{@code null} означает «тип не предназначен для межпроцессной доставки».
     * Именно белый список, а не исключение секретных полей: при добавлении нового
     * события забыть внести его сюда — значит потерять доставку и заметить это,
     * тогда как забыть исключить поле в чёрном списке — значит бесшумно опубликовать
     * секрет.
     */
    private static Map<String, String> describe(AuthEvent event) {
        Map<String, String> attributes = new LinkedHashMap<>();

        if (event instanceof SessionInvalidatedEvent e) {
            attributes.put("reason", e.reason());
            attributes.put("sessions", String.join(",", e.sessionPublicIds()));
            attributes.put("affectsAll", String.valueOf(e.affectsAll()));
            return attributes;
        }
        if (event instanceof PasswordChangedEvent e) {
            attributes.put("viaReset", String.valueOf(e.viaReset()));
            attributes.put("sessionsRevoked", String.valueOf(e.sessionsRevoked()));
            attributes.put("source", e.context().source().name());
            return attributes;
        }
        if (event instanceof BindingChangedEvent e) {
            attributes.put("binding", e.binding().name());
            attributes.put("action", e.action().name());
            if (e.target() != null) {
                attributes.put("target", e.target());
            }
            attributes.put("source", e.context().source().name());
            return attributes;
        }
        if (event instanceof SuspiciousActivityEvent e) {
            attributes.put("eventType", e.type().name());
            attributes.put("severity", e.severity().name());
            attributes.put("ip", e.context().ip().asMasked());
            if (e.detail() != null) {
                attributes.put("detail", e.detail());
            }
            return attributes;
        }
        if (event instanceof AccountLoginEvent e) {
            // Account целиком не передаётся: в нём хэш пароля. Ботам для уведомления
            // «новый вход» достаточно ника, маскированного адреса и признаков новизны.
            attributes.put("username", e.account().username());
            attributes.put("sessionId", e.session().publicId());
            attributes.put("ip", e.context().ip().asMasked());
            attributes.put("newDevice", String.valueOf(e.newDevice()));
            attributes.put("newCountry", String.valueOf(e.newCountry()));
            if (e.context().country() != null) {
                attributes.put("country", e.context().country());
            }
            if (e.context().city() != null) {
                attributes.put("city", e.context().city());
            }
            if (e.twoFactorUsed() != null) {
                attributes.put("twoFactor", e.twoFactorUsed().name());
            }
            return attributes;
        }
        if (event instanceof LoginApprovalRequestedEvent e) {
            // Токена подтверждения в событии нет по построению: бот выпускает свой,
            // получив это сообщение. Здесь уезжает только то, что попадёт в текст
            // запроса владельцу, — и адрес, как везде, в маскированном виде.
            attributes.put("username", e.username());
            attributes.put("method", e.method().name());
            attributes.put("ip", e.context().ip().asMasked());
            if (e.context().country() != null) {
                attributes.put("country", e.context().country());
            }
            if (e.context().city() != null) {
                attributes.put("city", e.context().city());
            }
            return attributes;
        }
        return null;
    }

    // ------------------------------------------------------------------ делегирование

    @Override
    public <T extends AuthEvent> @NotNull Subscription subscribe(@NotNull Class<T> eventType,
                                                                  @NotNull Consumer<T> handler) {
        return local.subscribe(eventType, handler);
    }

    @Override
    public <T extends AuthEvent> @NotNull Subscription subscribe(@NotNull Class<T> eventType,
                                                                  int priority,
                                                                  @NotNull Consumer<T> handler) {
        return local.subscribe(eventType, priority, handler);
    }

    @Override
    public void unsubscribeAll() {
        local.unsubscribeAll();
    }

    /** Идентификатор этого узла. */
    public @NotNull String nodeId() {
        return nodeId;
    }

    @Override
    public void close() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        local.unsubscribeAll();
    }
}
