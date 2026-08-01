package net.kofnetwork.auth.core.event;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.events.AccountLoginEvent;
import net.kofnetwork.auth.api.event.events.PasswordChangedEvent;
import net.kofnetwork.auth.api.event.events.RemoteEvent;
import net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.core.cache.CacheProvider;
import net.kofnetwork.auth.core.cache.NoopCacheProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class RedisEventBridgeTest {

    private static final String HASH = "$2a$12$секретныйхэшкоторыйнедолженуехатьвсеть";

    /**
     * Кэш-заглушка, запоминающая опубликованное и позволяющая «прислать» сообщение
     * так, будто оно пришло с другого узла.
     */
    private static final class RecordingCache extends NoopCacheProvider {

        final List<String> published = new CopyOnWriteArrayList<>();
        final List<Consumer<String>> subscribers = new ArrayList<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Long> publish(String channel, String message) {
            published.add(message);
            return CompletableFuture.completedFuture(1L);
        }

        @Override
        public Subscription subscribe(String channel, Consumer<String> handler) {
            subscribers.add(handler);
            return () -> subscribers.remove(handler);
        }

        void deliver(String message) {
            subscribers.forEach(handler -> handler.accept(message));
        }
    }

    private final RecordingCache cache = new RecordingCache();
    private final SimpleEventBus local = new SimpleEventBus(Runnable::run);
    private final RedisEventBridge bridge = new RedisEventBridge(local, cache, "узел-A");

    private static Account account() {
        return Account.newAccount(UUID.randomUUID(), "Steve", HASH, IpAddress.of("203.0.113.7"))
                .id(42L)
                .build();
    }

    private static Session session() {
        return Session.create(42L, null, SessionType.GAME, IpAddress.of("203.0.113.7"),
                "vanilla", Duration.ofHours(1), Duration.ofDays(7));
    }

    @Test
    void распределённое_событие_уходит_в_канал() {
        bridge.publish(SessionInvalidatedEvent.all(42L, "PASSWORD_CHANGED")).join();

        assertThat(cache.published).hasSize(1);
        assertThat(cache.published.get(0))
                .contains("SessionInvalidatedEvent")
                .contains("узел-A")
                .contains("PASSWORD_CHANGED");
    }

    @Test
    void локальное_событие_в_канал_не_уходит() {
        // AccountLoginFailedEvent помечен как нераспределённый: рассылать каждую
        // опечатку в пароле на всю сеть бессмысленно.
        var event = net.kofnetwork.auth.api.event.events.AccountLoginFailedEvent.of(
                42L, "Steve", net.kofnetwork.auth.api.model.LoginResultType.BAD_PASSWORD,
                AuthContext.system(), 1);

        bridge.publish(event).join();

        assertThat(cache.published).isEmpty();
    }

    @Test
    void хэш_пароля_не_попадает_в_канал() {
        // Главная причина, по которой по сети едет RemoteEvent, а не сам объект.
        bridge.publish(AccountLoginEvent.of(account(), session(), AuthContext.system(),
                null, true, false)).join();

        assertThat(cache.published).hasSize(1);
        assertThat(cache.published.get(0))
                .doesNotContain(HASH)
                .doesNotContain("passwordHash")
                .contains("Steve");
    }

    @Test
    void полный_адрес_не_попадает_в_канал() {
        AuthContext context = AuthContext.minecraft(IpAddress.of("203.0.113.7"), "lobby", 767, "vanilla");

        bridge.publish(AccountLoginEvent.of(account(), session(), context, null, true, false)).join();

        assertThat(cache.published.get(0))
                .doesNotContain("203.0.113.7")
                .contains("203.0.113.***");
    }

    @Test
    void чужое_сообщение_превращается_в_RemoteEvent() {
        List<RemoteEvent> received = new CopyOnWriteArrayList<>();
        local.subscribe(RemoteEvent.class, received::add);
        bridge.start();

        cache.deliver("""
                {"type":"SessionInvalidatedEvent","node":"узел-B","accountId":"42",
                 "occurredAt":"2026-08-01T10:00:00Z","reason":"ADMIN","affectsAll":"true"}
                """);

        assertThat(received).hasSize(1);
        RemoteEvent event = received.get(0);
        assertThat(event.isType(SessionInvalidatedEvent.class)).isTrue();
        assertThat(event.accountId()).isEqualTo(42L);
        assertThat(event.attribute("reason")).isEqualTo("ADMIN");
        assertThat(event.booleanAttribute("affectsAll")).isTrue();
        assertThat(event.nodeId()).isEqualTo("узел-B");
    }

    @Test
    void собственное_сообщение_игнорируется() {
        // Иначе узел обработал бы своё же событие дважды.
        List<RemoteEvent> received = new CopyOnWriteArrayList<>();
        local.subscribe(RemoteEvent.class, received::add);
        bridge.start();

        cache.deliver("""
                {"type":"SessionInvalidatedEvent","node":"узел-A","accountId":"42",
                 "occurredAt":"2026-08-01T10:00:00Z","reason":"ADMIN"}
                """);

        assertThat(received).isEmpty();
    }

    @Test
    void повреждённое_сообщение_не_ломает_подписку() {
        List<RemoteEvent> received = new CopyOnWriteArrayList<>();
        local.subscribe(RemoteEvent.class, received::add);
        bridge.start();

        cache.deliver("это не json");
        cache.deliver("""
                {"type":"PasswordChangedEvent","node":"узел-B","accountId":"7",
                 "occurredAt":"2026-08-01T10:00:00Z","viaReset":"true"}
                """);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).booleanAttribute("viaReset")).isTrue();
    }

    @Test
    void RemoteEvent_не_пересылается_обратно() {
        // Иначе два узла зациклили бы пересылку друг другу.
        RemoteEvent remote = new RemoteEvent("SomeEvent", "узел-B", 1L, Map.of(),
                java.time.Instant.now());

        assertThat(remote.isDistributed()).isFalse();

        bridge.publish(remote).join();

        assertThat(cache.published).isEmpty();
    }

    @Test
    void при_недоступном_кэше_работает_как_локальная_шина() {
        CacheProvider offline = new NoopCacheProvider();
        RedisEventBridge offlineBridge = new RedisEventBridge(local, offline, "узел-C");
        List<PasswordChangedEvent> received = new CopyOnWriteArrayList<>();
        local.subscribe(PasswordChangedEvent.class, received::add);

        offlineBridge.start();
        offlineBridge.publish(PasswordChangedEvent.of(1L, AuthContext.system(), false, true)).join();

        assertThat(received).hasSize(1);
    }

    @Test
    void подписка_и_публикация_делегируются_локальной_шине() {
        List<PasswordChangedEvent> received = new CopyOnWriteArrayList<>();

        bridge.subscribe(PasswordChangedEvent.class, received::add);
        bridge.publish(PasswordChangedEvent.of(9L, AuthContext.system(), true, false)).join();

        assertThat(received).hasSize(1);
        assertThat(received.get(0).viaReset()).isTrue();
    }

    @Test
    void сообщение_без_типа_пропускается() {
        List<RemoteEvent> received = new CopyOnWriteArrayList<>();
        local.subscribe(RemoteEvent.class, received::add);
        bridge.start();

        cache.deliver("{\"node\":\"узел-B\"}");

        assertThat(received).isEmpty();
    }

    @Test
    void неописанное_распределённое_событие_остаётся_локальным() {
        // Событие объявляет себя распределённым, но белого списка полей для него нет.
        // Отправлять его «как есть» нельзя — именно так и утекают секреты.
        var undescribed = new net.kofnetwork.auth.api.event.events.AccountRegisteredEvent(
                account(), AuthContext.system(), java.time.Instant.now());
        assertThat(undescribed.isDistributed()).isTrue();

        bridge.publish(undescribed).join();

        assertThat(cache.published).isEmpty();
    }

    @Test
    void getAndDelete_заглушки_не_возвращает_значений() {
        assertThat(new NoopCacheProvider().getAndDelete("любой").join()).isEqualTo(Optional.empty());
    }
}
