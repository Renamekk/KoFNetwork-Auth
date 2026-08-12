package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.LoginApprovalDecidedEvent;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.AccountStatus;
import net.kofnetwork.auth.api.model.ApprovalStatus;
import net.kofnetwork.auth.api.model.BotMessage;
import net.kofnetwork.auth.api.model.BotPlatform;
import net.kofnetwork.auth.api.model.DiscordBinding;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.LoginApproval;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.api.model.TelegramBinding;
import net.kofnetwork.auth.api.repository.BotOutboxRepository;
import net.kofnetwork.auth.api.repository.LoginApprovalRepository;
import net.kofnetwork.auth.api.service.AuditService;
import net.kofnetwork.auth.api.service.LinkService;
import net.kofnetwork.auth.api.service.LoginApprovalService;
import net.kofnetwork.auth.core.security.TokenGenerator;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Подтверждение входа кнопкой в мессенджере.
 *
 * <p>Здесь проверяется всё, чего не делал прежний механизм на предъявительском
 * токене: подтверждение существует только после проверки пароля, принимается только
 * от того, кому адресовано, применяется ровно один раз даже при одновременных
 * нажатиях и завершает <em>исходную</em> попытку входа, а не создаёт отдельную
 * сессию мессенджера.
 */
class LoginApprovalServiceTest {

    private static final long ACCOUNT = 7L;
    private static final long TELEGRAM_USER = 1001L;
    private static final long TELEGRAM_CHAT = 5005L;
    private static final long DISCORD_USER = 2002L;
    private static final UUID PLAYER = UUID.randomUUID();

    /**
     * Хранилище подтверждений в памяти с настоящей атомарностью решения.
     *
     * <p>Атомарность здесь не имитируется «на глазок»: {@code decide} выполняется под
     * блокировкой самой записи, ровно как {@code UPDATE ... WHERE status = 'PENDING'}
     * в базе. Иначе тест на два одновременных нажатия проверял бы поведение мока,
     * а не свойство, которое требуется от системы.
     */
    private static final class InMemoryApprovals implements LoginApprovalRepository {

        private final Map<String, LoginApproval> rows = new ConcurrentHashMap<>();
        private final AtomicInteger ids = new AtomicInteger();

        @Override
        public @NotNull CompletableFuture<LoginApproval> insert(@NotNull LoginApproval approval) {
            LoginApproval saved = approval.withId(ids.incrementAndGet());
            rows.put(saved.publicId(), saved);
            return CompletableFuture.completedFuture(saved);
        }

        @Override
        public @NotNull CompletableFuture<Optional<LoginApproval>> findByPublicId(
                @NotNull String publicId) {
            return CompletableFuture.completedFuture(Optional.ofNullable(rows.get(publicId)));
        }

        @Override
        public @NotNull CompletableFuture<Optional<LoginApproval>> findByAttemptId(
                @NotNull String attemptId) {
            return CompletableFuture.completedFuture(rows.values().stream()
                    .filter(row -> row.attemptId().equals(attemptId)).findFirst());
        }

        @Override
        public @NotNull CompletableFuture<Void> storeCompletedSession(@NotNull String publicId,
                                                                        long sessionId) {
            rows.computeIfPresent(publicId, (key, row) -> row.status() == ApprovalStatus.APPROVED
                    ? row.completedBy(gameSession()) : row);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NotNull CompletableFuture<ExchangeOutcome> consumeWebExchange(
                @NotNull String attemptId, @NotNull String proofHash, @NotNull Instant at) {
            LoginApproval[] outcome = {null};
            boolean[] consumed = {false};
            rows.replaceAll((key, row) -> {
                if (!row.attemptId().equals(attemptId)) {
                    return row;
                }
                outcome[0] = row;
                if (row.status() == ApprovalStatus.APPROVED && row.sessionId() != null
                        && row.exchangeConsumedAt() == null
                        && proofHash.equals(row.browserProofHash())) {
                    consumed[0] = true;
                    LoginApproval exchanged = row.exchangedAt(at);
                    outcome[0] = exchanged;
                    return exchanged;
                }
                return row;
            });
            return CompletableFuture.completedFuture(new ExchangeOutcome(consumed[0], outcome[0]));
        }

        @Override
        public @NotNull CompletableFuture<DecisionOutcome> decide(@NotNull String publicId,
                                                                   @NotNull ApprovalStatus decision,
                                                                   @NotNull Instant at,
                                                                   long decidedBy) {
            boolean[] applied = {false};
            LoginApproval current = rows.computeIfPresent(publicId, (key, existing) -> {
                if (existing.status() != ApprovalStatus.PENDING || existing.isExpired(at)) {
                    return existing;
                }
                applied[0] = true;
                return existing.decided(decision, at, decidedBy);
            });
            return CompletableFuture.completedFuture(
                    new DecisionOutcome(applied[0], current));
        }

        @Override
        public @NotNull CompletableFuture<Integer> expireOverdue(@NotNull Instant at) {
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public @NotNull CompletableFuture<Integer> supersedePending(long accountId,
                                                                     long keepId,
                                                                     @NotNull Instant at) {
            int[] count = {0};
            rows.replaceAll((key, existing) -> {
                if (existing.accountId() == accountId
                        && existing.id() < keepId
                        && (existing.status() == ApprovalStatus.PENDING
                        || (existing.requestSource() == net.kofnetwork.auth.api.model.EventSource.WEB
                        && existing.status() == ApprovalStatus.APPROVED
                        && existing.exchangeConsumedAt() == null))) {
                    count[0]++;
                    return existing.decided(ApprovalStatus.EXPIRED, at, 0L);
                }
                return existing;
            });
            return CompletableFuture.completedFuture(count[0]);
        }

        @Override
        public @NotNull CompletableFuture<Integer> deleteDecidedBefore(@NotNull Instant before) {
            return CompletableFuture.completedFuture(0);
        }
    }

    /** Очередь в памяти. */
    private static final class InMemoryOutbox implements BotOutboxRepository {

        private final List<BotMessage> messages = new ArrayList<>();
        private final AtomicInteger ids = new AtomicInteger();

        @Override
        public synchronized @NotNull CompletableFuture<BotMessage> append(
                @NotNull BotMessage message) {
            BotMessage saved = message.withId(ids.incrementAndGet());
            messages.add(saved);
            return CompletableFuture.completedFuture(saved);
        }

        @Override
        public @NotNull CompletableFuture<List<BotMessage>> readAfter(@NotNull BotPlatform platform,
                                                                       long after, int limit,
                                                                       @NotNull Instant now) {
            return CompletableFuture.completedFuture(messages.stream()
                    .filter(message -> message.platform() == platform && message.id() > after)
                    .limit(limit)
                    .toList());
        }

        @Override
        public @NotNull CompletableFuture<Long> cursor(@NotNull BotPlatform platform) {
            return CompletableFuture.completedFuture(0L);
        }

        @Override
        public @NotNull CompletableFuture<Long> acknowledge(@NotNull BotPlatform platform,
                                                             long cursor) {
            return CompletableFuture.completedFuture(cursor);
        }

        @Override
        public @NotNull CompletableFuture<Integer> deleteExpired(@NotNull Instant before) {
            return CompletableFuture.completedFuture(0);
        }

        synchronized List<BotMessage> ofKind(BotMessage.Kind kind) {
            return messages.stream().filter(message -> message.kind() == kind).toList();
        }
    }

    /** Шина, запоминающая опубликованное. */
    private static final class CapturingBus implements EventBus {

        private final List<AuthEvent> published = java.util.Collections.synchronizedList(
                new ArrayList<>());

        @Override
        public @NotNull CompletableFuture<Void> publish(@NotNull AuthEvent event) {
            published.add(event);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NotNull CompletableFuture<Void> publishAndAwait(@NotNull AuthEvent event) {
            return publish(event);
        }

        @Override
        public <T extends AuthEvent> @NotNull Subscription subscribe(@NotNull Class<T> type,
                                                                      @NotNull Consumer<T> handler) {
            return inactive();
        }

        @Override
        public <T extends AuthEvent> @NotNull Subscription subscribe(@NotNull Class<T> type,
                                                                      int priority,
                                                                      @NotNull Consumer<T> handler) {
            return inactive();
        }

        @Override
        public void unsubscribeAll() {
            published.clear();
        }

        private static Subscription inactive() {
            return new Subscription() {

                @Override
                public void close() {
                    // Отписывать нечего.
                }

                @Override
                public boolean isActive() {
                    return false;
                }
            };
        }

        List<LoginApprovalDecidedEvent> decisions() {
            synchronized (published) {
                return published.stream()
                        .filter(LoginApprovalDecidedEvent.class::isInstance)
                        .map(LoginApprovalDecidedEvent.class::cast)
                        .toList();
            }
        }
    }

    private InMemoryApprovals approvals;
    private InMemoryOutbox outbox;
    private CapturingBus events;
    private LinkService links;
    private LoginApprovalServiceImpl service;

    /** Сколько раз исходный вход был завершён. */
    private AtomicInteger completions;

    @BeforeEach
    void setUp() {
        approvals = new InMemoryApprovals();
        outbox = new InMemoryOutbox();
        events = new CapturingBus();
        links = mock(LinkService.class);
        completions = new AtomicInteger();

        AuditService audit = mock(AuditService.class);
        when(audit.log(anyLong(), any(), any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ConfigurationService config = mock(ConfigurationService.class);
        when(config.getDuration(any(ConfigFile.class), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));

        bindTelegram(true);
        when(links.findDiscord(ACCOUNT))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        service = new LoginApprovalServiceImpl(approvals, outbox, links, audit, events, config);
        service.attachCompleter(approval -> {
            completions.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(gameSession()));
        });
    }

    private void bindTelegram(boolean approvalEnabled) {
        TelegramBinding binding = new TelegramBinding(1L, ACCOUNT, TELEGRAM_USER, TELEGRAM_CHAT,
                null, null, null, null, true, approvalEnabled, Instant.now(), Instant.now());
        when(links.findTelegram(ACCOUNT))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(binding)));
    }

    private void bindDiscord() {
        DiscordBinding binding = new DiscordBinding(1L, ACCOUNT, DISCORD_USER, null, null, null,
                null, true, true, null, null, Instant.now(), Instant.now());
        when(links.findDiscord(ACCOUNT))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(binding)));
    }

    private static Session gameSession() {
        return new Session(1L, ACCOUNT, null, "сессия-игры", SessionType.GAME,
                IpAddress.of("10.0.0.1"), null, null, null, null,
                Instant.now(), Instant.now(), Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(86_400), false, null, null);
    }

    private static Account account() {
        return Account.newAccount(UUID.randomUUID(), "Игрок", "hash", IpAddress.of("10.0.0.1"))
                .id(ACCOUNT)
                .status(AccountStatus.ACTIVE)
                .build();
    }

    private LoginApproval request(BotPlatform platform, String attemptId) {
        var result = service.request(account(), PLAYER, attemptId, platform,
                AuthContext.minecraft(IpAddress.of("10.0.0.1"), "limbo-1", null, null)).join();
        assertThat(result.isSuccess()).isTrue();
        return result.value();
    }

    private LoginApproval webRequest(String attemptId, String proofHash) {
        var result = service.request(account(), null, attemptId, BotPlatform.TELEGRAM,
                AuthContext.web(IpAddress.of("10.0.0.1"), "KoF test browser"), proofHash).join();
        assertThat(result.isSuccess()).isTrue();
        return result.value();
    }

    // ================================================================ выпуск

    @Nested
    @DisplayName("Выпуск подтверждения")
    class Issuing {

        @Test
        @DisplayName("кладёт в очередь бота запрос с идентификатором и получателем")
        void кладётЗапросВОчередь() {
            LoginApproval approval = request(BotPlatform.TELEGRAM, "попытка-1");

            List<BotMessage> queued = outbox.ofKind(BotMessage.Kind.LOGIN_APPROVAL);
            assertThat(queued).hasSize(1);
            assertThat(queued.get(0).platform()).isEqualTo(BotPlatform.TELEGRAM);
            assertThat(queued.get(0).recipientId()).isEqualTo(TELEGRAM_USER);
            assertThat(queued.get(0).chatId()).isEqualTo(TELEGRAM_CHAT);
            assertThat(queued.get(0).get("approvalId")).isEqualTo(approval.publicId());
            assertThat(queued.get(0).get("ip"))
                    .as("полный адрес наружу не уходит")
                    .doesNotContain("10.0.0.1");
        }

        /**
         * Регрессия: раньше событие публиковалось независимо от того, есть ли кому
         * показать кнопку, и игрок ждал подтверждения, которого не будет.
         */
        @Test
        @DisplayName("без привязки подтверждение не выпускается")
        void безПривязкиНеВыпускается() {
            when(links.findTelegram(ACCOUNT))
                    .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

            var result = service.request(account(), PLAYER, "попытка", BotPlatform.TELEGRAM,
                    AuthContext.system()).join();

            assertThat(result.isFailure()).isTrue();
            assertThat(result.errorCode()).isEqualTo("APPROVAL_UNAVAILABLE");
        }

        @Test
        @DisplayName("выключенное подтверждение не выпускается")
        void выключенноеПодтверждениеНеВыпускается() {
            bindTelegram(false);

            var result = service.request(account(), PLAYER, "попытка", BotPlatform.TELEGRAM,
                    AuthContext.system()).join();

            assertThat(result.isFailure()).isTrue();
        }

        /**
         * Новая попытка обесценивает прежнюю: иначе в чате копятся кнопки, и нажатие
         * старой завершало бы вход, от которого игрок уже отказался.
         */
        @Test
        @DisplayName("новая попытка гасит прежнюю")
        void новаяПопыткаГаситПрежнюю() {
            LoginApproval first = request(BotPlatform.TELEGRAM, "попытка-1");
            request(BotPlatform.TELEGRAM, "попытка-2");

            var decision = service.decide(first.publicId(), BotPlatform.TELEGRAM,
                    TELEGRAM_USER, true).join();

            assertThat(decision.result())
                    .isEqualTo(LoginApprovalService.DecisionResult.EXPIRED);
            assertThat(completions).hasValue(0);
        }

        /**
         * Регрессия: две одновременные попытки гасили друг друга.
         *
         * <p>Условие «погасить всё, кроме своей» симметрично, и при параллельном
         * выпуске — вход в игру и вход на сайте одним человеком — каждая попытка
         * успевала объявить другую устаревшей. Владелец получал две кнопки, обе
         * мёртвые, и войти не мог ни одним способом, пока не начнёт заново.
         *
         * <p>Требуемое свойство: сколько бы попыток ни выпустили одновременно,
         * ровно одна остаётся действующей.
         */
        @Test
        @DisplayName("одновременные попытки не гасят друг друга")
        void одновременныеПопыткиНеГасятДругДруга() throws Exception {
            int parallel = 8;
            var start = new java.util.concurrent.CountDownLatch(1);
            var issued = java.util.Collections.synchronizedList(
                    new java.util.ArrayList<LoginApproval>());
            var pool = java.util.concurrent.Executors.newFixedThreadPool(parallel);
            try {
                List<java.util.concurrent.Future<?>> tasks = new java.util.ArrayList<>();
                for (int i = 0; i < parallel; i++) {
                    String attemptId = "попытка-" + i;
                    tasks.add(pool.submit(() -> {
                        start.await();
                        issued.add(request(BotPlatform.TELEGRAM, attemptId));
                        return null;
                    }));
                }
                start.countDown();
                for (var task : tasks) {
                    task.get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            List<LoginApproval> alive = issued.stream()
                    .map(approval -> approvals.findByPublicId(approval.publicId()).join())
                    .flatMap(Optional::stream)
                    .filter(approval -> approval.status() == ApprovalStatus.PENDING)
                    .toList();

            assertThat(alive)
                    .as("хотя бы одна кнопка обязана остаться рабочей")
                    .hasSize(1);
            assertThat(service.decide(alive.get(0).publicId(), BotPlatform.TELEGRAM,
                            TELEGRAM_USER, true).join().result())
                    .isEqualTo(LoginApprovalService.DecisionResult.APPLIED);
        }
    }

    // ================================================================ решение

    @Nested
    @DisplayName("Решение владельца")
    class Deciding {

        @Test
        @DisplayName("WEB-вкладка получает одобренную сессию только своим proof и ровно один раз")
        void вебОбменОднократенИПривязанКВкладке() {
            String proof = "a".repeat(64);
            LoginApproval approval = webRequest("12345678-1234-1234-1234-123456789abc",
                    TokenGenerator.hash(proof));

            assertThat(service.webStatus(approval.attemptId(), "b".repeat(64)).join().status())
                    .isEqualTo(ApprovalStatus.EXPIRED);
            assertThat(service.webStatus(approval.attemptId(), proof).join().status())
                    .isEqualTo(ApprovalStatus.PENDING);

            service.decide(approval.publicId(), BotPlatform.TELEGRAM, TELEGRAM_USER, true).join();
            assertThat(service.webStatus(approval.attemptId(), proof).join().ready()).isTrue();

            var first = service.exchangeWeb(approval.attemptId(), proof).join();
            var replay = service.exchangeWeb(approval.attemptId(), proof).join();
            assertThat(first.consumed()).isTrue();
            assertThat(first.accountId()).isEqualTo(ACCOUNT);
            assertThat(first.sessionId()).isEqualTo(1L);
            assertThat(replay.consumed()).isFalse();
            assertThat(service.webStatus(approval.attemptId(), proof).join().ready()).isFalse();
        }

        @Test
        @DisplayName("новая WEB-вкладка гасит одобренную, но ещё не выданную старую")
        void новаяВебВкладкаГаситСтаруюДоОбмена() {
            String firstProof = "c".repeat(64);
            LoginApproval first = webRequest("12345678-1234-1234-1234-123456789abd",
                    TokenGenerator.hash(firstProof));
            service.decide(first.publicId(), BotPlatform.TELEGRAM, TELEGRAM_USER, true).join();

            webRequest("12345678-1234-1234-1234-123456789abe",
                    TokenGenerator.hash("d".repeat(64)));

            assertThat(service.webStatus(first.attemptId(), firstProof).join().status())
                    .isEqualTo(ApprovalStatus.EXPIRED);
            assertThat(service.exchangeWeb(first.attemptId(), firstProof).join().consumed()).isFalse();
        }

        @Test
        @DisplayName("одобрение завершает исходный вход, а не создаёт сессию мессенджера")
        void одобрениеЗавершаетИсходныйВход() {
            LoginApproval approval = request(BotPlatform.TELEGRAM, "попытка-1");

            var decision = service.decide(approval.publicId(), BotPlatform.TELEGRAM,
                    TELEGRAM_USER, true).join();

            assertThat(decision.isApplied()).isTrue();
            assertThat(decision.status()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(completions)
                    .as("одобрение обязано довести до конца ту попытку, что его ждала")
                    .hasValue(1);

            List<LoginApprovalDecidedEvent> published = events.decisions();
            assertThat(published).hasSize(1);
            assertThat(published.get(0).isApproved()).isTrue();
            assertThat(published.get(0).playerUuid())
                    .as("прокси должен понять, кого переводить дальше")
                    .isEqualTo(PLAYER);
            assertThat(published.get(0).attemptId()).isEqualTo("попытка-1");
            assertThat(published.get(0).sessionPublicId()).isEqualTo("сессия-игры");
        }

        @Test
        @DisplayName("отказ не создаёт сессии и сообщает прокси")
        void отказНеСоздаётСессии() {
            LoginApproval approval = request(BotPlatform.TELEGRAM, "попытка-1");

            var decision = service.decide(approval.publicId(), BotPlatform.TELEGRAM,
                    TELEGRAM_USER, false).join();

            assertThat(decision.isApplied()).isTrue();
            assertThat(decision.status()).isEqualTo(ApprovalStatus.DENIED);
            assertThat(completions).hasValue(0);
            assertThat(events.decisions()).singleElement()
                    .satisfies(event -> assertThat(event.isApproved()).isFalse());
        }

        /**
         * Регрессия: подтвердить чужой вход мог кто угодно, кто узнал идентификатор
         * запроса, — а он виден в {@code callback_data} кнопки.
         */
        @Test
        @DisplayName("нажатие чужим пользователем отвергается")
        void нажатиеЧужимПользователемОтвергается() {
            LoginApproval approval = request(BotPlatform.TELEGRAM, "попытка-1");

            var decision = service.decide(approval.publicId(), BotPlatform.TELEGRAM,
                    TELEGRAM_USER + 1, true).join();

            assertThat(decision.result())
                    .isEqualTo(LoginApprovalService.DecisionResult.FOREIGN);
            assertThat(completions).hasValue(0);
            assertThat(service.find(approval.publicId()).join())
                    .get()
                    .satisfies(row -> assertThat(row.status()).isEqualTo(ApprovalStatus.PENDING));
        }

        /**
         * Та же кнопка, но платформа другая. Без сверки платформы Discord-пользователь
         * с совпавшим числовым идентификатором подтвердил бы чужой вход.
         */
        @Test
        @DisplayName("нажатие с другой платформы отвергается")
        void нажатиеСДругойПлатформыОтвергается() {
            LoginApproval approval = request(BotPlatform.TELEGRAM, "попытка-1");

            var decision = service.decide(approval.publicId(), BotPlatform.DISCORD,
                    TELEGRAM_USER, true).join();

            assertThat(decision.result())
                    .isEqualTo(LoginApprovalService.DecisionResult.FOREIGN);
        }

        @Test
        @DisplayName("повторное нажатие возвращает уже принятое решение")
        void повторноеНажатиеВозвращаетПринятоеРешение() {
            LoginApproval approval = request(BotPlatform.TELEGRAM, "попытка-1");
            service.decide(approval.publicId(), BotPlatform.TELEGRAM, TELEGRAM_USER, true).join();

            var second = service.decide(approval.publicId(), BotPlatform.TELEGRAM,
                    TELEGRAM_USER, true).join();

            assertThat(second.result())
                    .isEqualTo(LoginApprovalService.DecisionResult.ALREADY_DECIDED);
            assertThat(second.status()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(completions)
                    .as("вторая кнопка не должна создавать вторую сессию")
                    .hasValue(1);
        }

        @Test
        @DisplayName("несуществующий запрос не находится")
        void несуществующийЗапросНеНаходится() {
            var decision = service.decide("нет-такого", BotPlatform.TELEGRAM,
                    TELEGRAM_USER, true).join();

            assertThat(decision.result())
                    .isEqualTo(LoginApprovalService.DecisionResult.NOT_FOUND);
        }

        /**
         * Два нажатия одновременно — обычное дело при двойном тапе и при доставке
         * сообщения дважды. Ровно одно обязано выиграть.
         */
        @Test
        @DisplayName("одновременные нажатия завершают вход ровно один раз")
        void одновременныеНажатияЗавершаютВходОдинРаз() throws Exception {
            LoginApproval approval = request(BotPlatform.TELEGRAM, "попытка-1");

            int attempts = 16;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            List<LoginApprovalService.Decision> results =
                    java.util.Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    start.await();
                    results.add(service.decide(approval.publicId(), BotPlatform.TELEGRAM,
                            TELEGRAM_USER, true).join());
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

            assertThat(results).hasSize(attempts);
            assertThat(results.stream().filter(LoginApprovalService.Decision::isApplied).count())
                    .as("решение принимается ровно один раз")
                    .isEqualTo(1);
            assertThat(completions).hasValue(1);
            assertThat(events.decisions())
                    .as("проигравшие нажатия не должны рассылать события")
                    .hasSize(1);
        }

        @Test
        @DisplayName("истёкший запрос не подтверждается")
        void истёкшийЗапросНеПодтверждается() {
            LoginApproval approval = request(BotPlatform.TELEGRAM, "попытка-1");
            // Отматываем срок в прошлое тем же способом, каким это делает время.
            approvals.rows.computeIfPresent(approval.publicId(), (key, row) ->
                    new LoginApproval(row.id(), row.publicId(), row.accountId(), row.username(),
                            row.playerUuid(), row.attemptId(), row.requestSource(),
                            row.requestPlatform(), row.userAgent(), row.deviceFingerprint(),
                            row.platform(), row.recipientId(),
                            row.chatId(), row.requestIp(), row.server(), row.country(), row.city(),
                            ApprovalStatus.PENDING, row.issuedAt(),
                            Instant.now().minusSeconds(1), null, null, row.browserProofHash(),
                            row.sessionId(), row.exchangeConsumedAt()));

            var decision = service.decide(approval.publicId(), BotPlatform.TELEGRAM,
                    TELEGRAM_USER, true).join();

            assertThat(decision.result())
                    .isEqualTo(LoginApprovalService.DecisionResult.EXPIRED);
            assertThat(completions).hasValue(0);
        }
    }

    // ================================================================ Discord

    @Nested
    @DisplayName("Discord обрабатывается как Discord")
    class DiscordHandling {

        /**
         * Регрессия: запрос Discord уезжал в телеграмный путь, а решение
         * записывалось с контекстом Telegram.
         */
        @Test
        @DisplayName("запрос адресуется в Discord и решается там же")
        void запросАдресуетсяВDiscord() {
            bindDiscord();

            LoginApproval approval = request(BotPlatform.DISCORD, "попытка-d");

            assertThat(approval.platform()).isEqualTo(BotPlatform.DISCORD);
            assertThat(approval.recipientId()).isEqualTo(DISCORD_USER);
            assertThat(outbox.ofKind(BotMessage.Kind.LOGIN_APPROVAL))
                    .singleElement()
                    .satisfies(message -> assertThat(message.platform())
                            .isEqualTo(BotPlatform.DISCORD));

            var decision = service.decide(approval.publicId(), BotPlatform.DISCORD,
                    DISCORD_USER, true).join();

            assertThat(decision.isApplied()).isTrue();
            assertThat(events.decisions()).singleElement()
                    .satisfies(event -> assertThat(event.platform())
                            .isEqualTo(BotPlatform.DISCORD));
        }
    }
}
