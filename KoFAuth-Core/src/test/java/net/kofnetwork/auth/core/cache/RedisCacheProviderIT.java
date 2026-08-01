package net.kofnetwork.auth.core.cache;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.core.config.YamlConfigurationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка кэша на настоящем Redis.
 *
 * <p>Юнит-тестами эти свойства не проверяются принципиально: атомарность
 * {@code GETDEL}, корректность скользящего окна на Lua и доставка Pub/Sub существуют
 * только на стороне сервера. Мок подтвердил бы лишь то, что мы правильно вызываем мок.
 */
@Testcontainers
class RedisCacheProviderIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @TempDir
    Path configDir;

    private RedisCacheProvider cache;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(configDir.resolve(ConfigFile.DATABASE.fileName()), """
                redis:
                  host: %s
                  port: %d
                  database: 0
                  key-prefix: "kofauth-test:"
                  timeout: 3s
                """.formatted(REDIS.getHost(), REDIS.getFirstMappedPort()), StandardCharsets.UTF_8);

        YamlConfigurationService config = new YamlConfigurationService(configDir, Runnable::run);
        config.initialize();
        cache = RedisCacheProvider.connect(config);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.deleteByPattern("*").join();
            cache.close();
        }
    }

    @Test
    void записывает_и_читает_значение() {
        cache.set("ключ", "значение", Duration.ofMinutes(1)).join();

        assertThat(cache.get("ключ").join()).contains("значение");
        assertThat(cache.exists("ключ").join()).isTrue();
    }

    @Test
    void отсутствующий_ключ_даёт_пустой_результат() {
        assertThat(cache.get("нет-такого").join()).isEmpty();
        assertThat(cache.exists("нет-такого").join()).isFalse();
    }

    @Test
    void значение_исчезает_после_истечения_срока() throws InterruptedException {
        cache.set("короткий", "значение", Duration.ofMillis(300)).join();
        assertThat(cache.get("короткий").join()).contains("значение");

        Thread.sleep(600);

        assertThat(cache.get("короткий").join()).isEmpty();
    }

    @Test
    void setIfAbsent_занимает_ключ_только_один_раз() {
        assertThat(cache.setIfAbsent("замок", "первый", Duration.ofMinutes(1)).join()).isTrue();
        assertThat(cache.setIfAbsent("замок", "второй", Duration.ofMinutes(1)).join()).isFalse();

        assertThat(cache.get("замок").join()).contains("первый");
    }

    @Test
    void getAndDelete_атомарен_и_срабатывает_однократно() {
        // Ключевое свойство для одноразовых токенов подтверждения входа:
        // двойное нажатие кнопки в Telegram не должно создать две сессии.
        cache.set("одноразовый", "токен", Duration.ofMinutes(1)).join();

        List<Optional<String>> results = IntStream.range(0, 20)
                .parallel()
                .mapToObj(i -> cache.getAndDelete("одноразовый").join())
                .toList();

        assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
        assertThat(cache.exists("одноразовый").join()).isFalse();
    }

    @Test
    void работает_с_хэшами() {
        cache.setHash("сессия", Map.of("accountId", "42", "ip", "203.0.113.7"),
                Duration.ofMinutes(1)).join();

        assertThat(cache.getHash("сессия").join())
                .containsEntry("accountId", "42")
                .containsEntry("ip", "203.0.113.7");
        assertThat(cache.getHashField("сессия", "accountId").join()).contains("42");
        assertThat(cache.getHashField("сессия", "нет").join()).isEmpty();
    }

    @Test
    void счётчик_растёт_и_срок_задаётся_только_при_создании() throws InterruptedException {
        assertThat(cache.increment("счётчик", Duration.ofMillis(700)).join()).isEqualTo(1L);
        Thread.sleep(300);
        assertThat(cache.increment("счётчик", Duration.ofMillis(700)).join()).isEqualTo(2L);

        // Срок отсчитывается от первого вызова: окно не должно продлеваться
        // каждым событием, иначе оно никогда не закроется.
        Thread.sleep(600);
        assertThat(cache.get("счётчик").join()).isEmpty();
    }

    @Test
    void скользящее_окно_считает_события() {
        for (int i = 1; i <= 5; i++) {
            assertThat(cache.incrementSlidingWindow("окно", Duration.ofMinutes(1)).join())
                    .isEqualTo(i);
        }

        assertThat(cache.countSlidingWindow("окно", Duration.ofMinutes(1)).join()).isEqualTo(5L);
    }

    @Test
    void скользящее_окно_забывает_старые_события() throws InterruptedException {
        cache.incrementSlidingWindow("окно", Duration.ofMillis(400)).join();
        cache.incrementSlidingWindow("окно", Duration.ofMillis(400)).join();
        assertThat(cache.countSlidingWindow("окно", Duration.ofMillis(400)).join()).isEqualTo(2L);

        Thread.sleep(500);

        // Именно за этим окно скользящее: при фиксированном лимит «10 в минуту»
        // позволял бы сделать 20 запросов на стыке двух минут.
        assertThat(cache.countSlidingWindow("окно", Duration.ofMillis(400)).join()).isZero();
    }

    @Test
    void события_в_одну_миллисекунду_не_сливаются() {
        // Метка каждого события уникальна, иначе быстрый перебор считался бы
        // как одна попытка и обходил ограничение.
        List<Long> counts = IntStream.range(0, 30)
                .mapToObj(i -> cache.incrementSlidingWindow("быстрое-окно", Duration.ofMinutes(1)).join())
                .toList();

        assertThat(counts.get(counts.size() - 1)).isEqualTo(30L);
    }

    @Test
    void доставляет_сообщения_подписчику() throws InterruptedException {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        try (CacheProvider.Subscription ignored = cache.subscribe("канал", message -> {
            received.add(message);
            latch.countDown();
        })) {
            // Подписка устанавливается асинхронно.
            Thread.sleep(300);
            cache.publish("канал", "первое").join();
            cache.publish("канал", "второе").join();

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactly("первое", "второе");
        }
    }

    @Test
    void отписка_прекращает_доставку() throws InterruptedException {
        List<String> received = new CopyOnWriteArrayList<>();
        CacheProvider.Subscription subscription = cache.subscribe("канал", received::add);
        Thread.sleep(300);

        subscription.close();
        Thread.sleep(200);
        cache.publish("канал", "после отписки").join();
        Thread.sleep(300);

        assertThat(received).isEmpty();
    }

    @Test
    void ошибка_в_обработчике_не_ломает_подписку() throws InterruptedException {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (CacheProvider.Subscription ignored = cache.subscribe("канал", message -> {
            if (message.startsWith("плохое")) {
                throw new IllegalStateException("обработчик упал");
            }
            received.add(message);
            latch.countDown();
        })) {
            Thread.sleep(300);
            cache.publish("канал", "плохое сообщение").join();
            cache.publish("канал", "хорошее сообщение").join();

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactly("хорошее сообщение");
        }
    }

    @Test
    void ключи_изолированы_префиксом() {
        cache.set("изолированный", "значение", Duration.ofMinutes(1)).join();

        // keys возвращает имена без префикса — вызывающий о нём не знает.
        assertThat(cache.keys("*").join()).contains("изолированный");
    }

    @Test
    void удаление_по_шаблону() {
        cache.set("группа:один", "a", Duration.ofMinutes(1)).join();
        cache.set("группа:два", "b", Duration.ofMinutes(1)).join();
        cache.set("другое", "c", Duration.ofMinutes(1)).join();

        assertThat(cache.deleteByPattern("группа:*").join()).isEqualTo(2L);

        assertThat(cache.exists("группа:один").join()).isFalse();
        assertThat(cache.exists("другое").join()).isTrue();
    }

    @Test
    void сообщает_о_доступности() {
        assertThat(cache.isAvailable()).isTrue();
        assertThat(cache.providerName()).isEqualTo("redis");

        cache.close();

        assertThat(cache.isAvailable()).isFalse();
    }

    @Test
    void после_закрытия_операции_возвращают_значения_по_умолчанию() {
        // Отказ кэша не должен ронять аутентификацию: для вызывающего это
        // тот же промах, после которого он идёт в MySQL.
        cache.set("ключ", "значение", Duration.ofMinutes(1)).join();
        cache.close();

        CompletableFuture<Optional<String>> read = cache.get("ключ");

        assertThat(read.join()).isEmpty();
        assertThat(cache.exists("ключ").join()).isFalse();
    }
}
