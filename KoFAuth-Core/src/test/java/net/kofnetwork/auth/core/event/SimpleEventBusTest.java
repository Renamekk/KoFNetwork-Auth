package net.kofnetwork.auth.core.event;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.PasswordChangedEvent;
import net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleEventBusTest {

    /** Синхронный executor: тест не должен зависеть от гонок планировщика. */
    private final SimpleEventBus bus = new SimpleEventBus(Runnable::run);

    private static PasswordChangedEvent passwordChanged() {
        return PasswordChangedEvent.of(42L, AuthContext.system(), false, true);
    }

    @Test
    void доставляет_событие_подписчику() {
        AtomicInteger received = new AtomicInteger();

        bus.subscribe(PasswordChangedEvent.class, event -> received.incrementAndGet());
        bus.publish(passwordChanged()).join();

        assertThat(received.get()).isEqualTo(1);
    }

    @Test
    void не_доставляет_событие_подписчику_другого_типа() {
        AtomicInteger received = new AtomicInteger();

        bus.subscribe(SessionInvalidatedEvent.class, event -> received.incrementAndGet());
        bus.publish(passwordChanged()).join();

        assertThat(received.get()).isZero();
    }

    @Test
    void подписка_на_базовый_тип_получает_все_события() {
        // Так работает аудит: один обработчик на всё.
        AtomicInteger received = new AtomicInteger();

        bus.subscribe(AuthEvent.class, event -> received.incrementAndGet());
        bus.publish(passwordChanged()).join();
        bus.publish(SessionInvalidatedEvent.all(42L, "TEST")).join();

        assertThat(received.get()).isEqualTo(2);
    }

    @Test
    void обработчики_вызываются_в_порядке_приоритета() {
        List<String> order = new CopyOnWriteArrayList<>();

        bus.subscribe(PasswordChangedEvent.class, 200, event -> order.add("поздний"));
        bus.subscribe(PasswordChangedEvent.class, 10, event -> order.add("ранний"));
        bus.subscribe(PasswordChangedEvent.class, 100, event -> order.add("средний"));

        bus.publish(passwordChanged()).join();

        assertThat(order).containsExactly("ранний", "средний", "поздний");
    }

    @Test
    void исключение_в_обработчике_не_мешает_остальным() {
        // Главное требование к шине: упавшее уведомление в Telegram не должно
        // помешать игроку войти.
        List<String> completed = new CopyOnWriteArrayList<>();

        bus.subscribe(PasswordChangedEvent.class, 10, event -> {
            throw new IllegalStateException("обработчик упал");
        });
        bus.subscribe(PasswordChangedEvent.class, 20, event -> completed.add("второй"));

        bus.publish(passwordChanged()).join();

        assertThat(completed).containsExactly("второй");
    }

    @Test
    void исключение_в_обработчике_не_всплывает_публикующему() {
        bus.subscribe(PasswordChangedEvent.class, event -> {
            throw new IllegalStateException("обработчик упал");
        });

        // join() не должен бросить.
        bus.publish(passwordChanged()).join();
    }

    @Test
    void отписка_прекращает_доставку() {
        AtomicInteger received = new AtomicInteger();

        EventBus.Subscription subscription =
                bus.subscribe(PasswordChangedEvent.class, event -> received.incrementAndGet());
        bus.publish(passwordChanged()).join();
        assertThat(received.get()).isEqualTo(1);

        subscription.close();
        bus.publish(passwordChanged()).join();

        assertThat(received.get()).isEqualTo(1);
        assertThat(subscription.isActive()).isFalse();
    }

    @Test
    void повторная_отписка_безопасна() {
        EventBus.Subscription subscription =
                bus.subscribe(PasswordChangedEvent.class, event -> {
                });

        subscription.close();
        subscription.close();

        assertThat(subscription.isActive()).isFalse();
    }

    @Test
    void unsubscribeAll_снимает_все_подписки() {
        AtomicInteger received = new AtomicInteger();
        bus.subscribe(PasswordChangedEvent.class, event -> received.incrementAndGet());
        bus.subscribe(AuthEvent.class, event -> received.incrementAndGet());
        assertThat(bus.subscriptionCount()).isEqualTo(2);

        bus.unsubscribeAll();
        bus.publish(passwordChanged()).join();

        assertThat(received.get()).isZero();
        assertThat(bus.subscriptionCount()).isZero();
    }

    @Test
    void событие_без_подписчиков_не_вызывает_ошибок() {
        bus.publish(passwordChanged()).join();
    }

    @Test
    void несколько_подписчиков_одного_типа_получают_событие() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        bus.subscribe(PasswordChangedEvent.class, event -> first.incrementAndGet());
        bus.subscribe(PasswordChangedEvent.class, event -> second.incrementAndGet());
        bus.publish(passwordChanged()).join();

        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(1);
    }

    @Test
    void обработчик_получает_событие_с_нужными_данными() {
        AtomicInteger accountId = new AtomicInteger();

        bus.subscribe(PasswordChangedEvent.class,
                event -> accountId.set(Math.toIntExact(event.accountIdValue())));
        bus.publish(passwordChanged()).join();

        assertThat(accountId.get()).isEqualTo(42);
    }
}
