package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.SessionInvalidatedEvent;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.model.SessionType;
import net.kofnetwork.auth.api.repository.DeviceRepository;
import net.kofnetwork.auth.api.repository.SessionRepository;
import net.kofnetwork.auth.core.cache.LocalCacheProvider;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Отзыв сессий и охват события.
 *
 * <p>Регрессия на самую дорогую ошибку системы: пустой перечень отозванных сессий
 * означал «все». Обычный вход при {@code max-concurrent: 1} вызывает
 * {@code revokeAll(accountId, exceptNewSession)}; у игрока, входящего впервые или
 * после истечения прежней сессии, отзывать нечего — и событие уезжало как «отозваны
 * все», после чего прокси выбрасывал того, кто секунду назад ввёл верный пароль.
 * Со второй попытки вход проходил, что и делало ошибку такой запутанной.
 */
class SessionInvalidationTest {

    /** Подписка, которую этот двойник не ведёт: события он только запоминает. */
    private static final EventBus.Subscription NO_SUBSCRIPTION = new EventBus.Subscription() {

        @Override
        public void close() {
            // Отписывать нечего.
        }

        @Override
        public boolean isActive() {
            return false;
        }
    };

    /** Шина, запоминающая опубликованное. */
    private static final class CapturingBus implements EventBus {

        private final List<AuthEvent> published = new ArrayList<>();

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
            return NO_SUBSCRIPTION;
        }

        @Override
        public <T extends AuthEvent> @NotNull Subscription subscribe(@NotNull Class<T> type,
                                                                      int priority,
                                                                      @NotNull Consumer<T> handler) {
            return NO_SUBSCRIPTION;
        }

        @Override
        public void unsubscribeAll() {
            published.clear();
        }

        List<SessionInvalidatedEvent> invalidations() {
            return published.stream()
                    .filter(SessionInvalidatedEvent.class::isInstance)
                    .map(SessionInvalidatedEvent.class::cast)
                    .toList();
        }
    }

    private static final long ACCOUNT = 42L;

    private SessionRepository repository;
    private CapturingBus events;
    private SessionServiceImpl sessions;

    @BeforeEach
    void setUp() {
        repository = mock(SessionRepository.class);
        events = new CapturingBus();

        ConfigurationService config = mock(ConfigurationService.class);
        when(config.getDuration(any(ConfigFile.class), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(config.getBoolean(any(ConfigFile.class), anyString(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(config.getInt(any(ConfigFile.class), anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        sessions = new SessionServiceImpl(repository, mock(DeviceRepository.class),
                new LocalCacheProvider(), config, events);
    }

    private static Session sessionWith(String publicId) {
        return new Session(1L, ACCOUNT, null, publicId, SessionType.GAME,
                IpAddress.of("10.0.0.1"), null, null, null, null,
                Instant.now(), Instant.now(), Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(86_400), false, null, null);
    }

    private void activeSessions(Session... active) {
        when(repository.findActiveByAccount(eq(ACCOUNT), any(Instant.class)))
                .thenReturn(CompletableFuture.completedFuture(List.of(active)));
    }

    private void revokesRows(int rows) {
        when(repository.revokeAllForAccount(eq(ACCOUNT), any(), any(Instant.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(rows));
    }

    @Nested
    @DisplayName("Охват события")
    class Scope {

        /**
         * Главная регрессия: вход после истечения прежней сессии.
         *
         * <p>Прежних сессий нет, отзывать нечего. Событие обязано либо не появиться
         * вовсе, либо иметь охват NONE — но ни в коем случае не ALL.
         */
        @Test
        @DisplayName("вход после истечения прежней сессии не публикует отзыв")
        void входПослеИстеченияНеПубликуетОтзыв() {
            activeSessions();
            revokesRows(0);

            int revoked = sessions.revokeAll(ACCOUNT, "новая-сессия",
                    Session.REASON_LOGOUT_ALL).join();

            assertThat(revoked).isZero();
            assertThat(events.invalidations())
                    .as("отзыв, которому нечего отзывать, не должен звать прокси выбрасывать игроков")
                    .isEmpty();
        }

        @Test
        @DisplayName("отзыв прежней сессии называет её поимённо")
        void отзывПрежнейСессииНазываетЕёПоимённо() {
            activeSessions(sessionWith("старая"), sessionWith("новая"));
            revokesRows(1);

            sessions.revokeAll(ACCOUNT, "новая", Session.REASON_LOGOUT_ALL).join();

            List<SessionInvalidatedEvent> published = events.invalidations();
            assertThat(published).hasSize(1);
            assertThat(published.get(0).scope())
                    .isEqualTo(SessionInvalidatedEvent.Scope.SOME);
            assertThat(published.get(0).affects("старая")).isTrue();
            assertThat(published.get(0).affects("новая"))
                    .as("только что выданная сессия не может быть отозвана этим же вызовом")
                    .isFalse();
        }

        @Test
        @DisplayName("«выйти со всех устройств» задевает всё")
        void выйтиСоВсехУстройствЗадеваетВсё() {
            activeSessions(sessionWith("первая"), sessionWith("вторая"));
            revokesRows(2);

            sessions.revokeAll(ACCOUNT, null, Session.REASON_LOGOUT_ALL).join();

            List<SessionInvalidatedEvent> published = events.invalidations();
            assertThat(published).hasSize(1);
            assertThat(published.get(0).scope()).isEqualTo(SessionInvalidatedEvent.Scope.ALL);
            assertThat(published.get(0).affects("какая-угодно")).isTrue();
        }

        @Test
        @DisplayName("«выйти со всех» без единой сессии не публикует ничего")
        void выйтиСоВсехБезСессийНеПубликуетНичего() {
            activeSessions();
            revokesRows(0);

            sessions.revokeAll(ACCOUNT, null, Session.REASON_PASSWORD_CHANGED).join();

            assertThat(events.invalidations()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Точечный отзыв")
    class PointRevoke {

        /**
         * Регрессия: точечный отзыв не доходил до других процессов.
         *
         * <p>Публиковался только {@code AccountLogoutEvent}, у которого нет описания
         * для межпроцессной шины, — то есть он оставался локальным. Игрок, чью сессию
         * завершили из личного кабинета, продолжал играть на прокси.
         */
        @Test
        @DisplayName("публикует событие с точным идентификатором сессии")
        void публикуетСобытиеСТочнымИдентификатором() {
            Session session = sessionWith("живая");
            when(repository.findByPublicId("живая"))
                    .thenReturn(CompletableFuture.completedFuture(Optional.of(session)));
            when(repository.revoke(eq("живая"), any(Instant.class), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(true));

            sessions.revoke("живая", Session.REASON_LOGOUT).join();

            List<SessionInvalidatedEvent> published = events.invalidations();
            assertThat(published)
                    .as("без этого события отзыв не покидает свой процесс")
                    .hasSize(1);
            assertThat(published.get(0).scope()).isEqualTo(SessionInvalidatedEvent.Scope.SOME);
            assertThat(published.get(0).affects("живая")).isTrue();
            assertThat(published.get(0).affects("чужая")).isFalse();
        }
    }

    @Nested
    @DisplayName("Модель события")
    class EventModel {

        @Test
        @DisplayName("SOME с пустым перечнем нормализуется в NONE")
        void someСПустымПеречнёмНормализуетсяВNone() {
            SessionInvalidatedEvent event =
                    SessionInvalidatedEvent.some(ACCOUNT, List.of(), "TEST");

            assertThat(event.scope()).isEqualTo(SessionInvalidatedEvent.Scope.NONE);
            assertThat(event.isNoop()).isTrue();
            assertThat(event.affects("что-угодно")).isFalse();
        }

        @Test
        @DisplayName("ALL не хранит перечня")
        void allНеХранитПеречня() {
            SessionInvalidatedEvent event = new SessionInvalidatedEvent(ACCOUNT,
                    SessionInvalidatedEvent.Scope.ALL, List.of("лишняя"), "TEST", Instant.now());

            assertThat(event.sessionPublicIds()).isEmpty();
            assertThat(event.affects("любая")).isTrue();
        }

        @Test
        @DisplayName("неизвестный охват разбирается как NONE")
        void неизвестныйОхватРазбираетсяКакNone() {
            assertThat(SessionInvalidatedEvent.Scope.parse(null))
                    .isEqualTo(SessionInvalidatedEvent.Scope.NONE);
            assertThat(SessionInvalidatedEvent.Scope.parse("нечто"))
                    .isEqualTo(SessionInvalidatedEvent.Scope.NONE);
            assertThat(SessionInvalidatedEvent.Scope.parse("some"))
                    .isEqualTo(SessionInvalidatedEvent.Scope.SOME);
        }
    }

    @Nested
    @DisplayName("Отказ хранилища состояния")
    class StoreFailure {

        /**
         * Регрессия на fail-open: отказ хранилища возвращал «состояние CONNECTING»,
         * то есть был неотличим от только что подключившегося игрока.
         */
        @Test
        @DisplayName("чтение состояния при отказе завершается ошибкой, а не значением по умолчанию")
        void чтениеСостоянияПриОтказеЗавершаетсяОшибкой() {
            SessionServiceImpl broken = new SessionServiceImpl(repository,
                    mock(DeviceRepository.class),
                    new net.kofnetwork.auth.core.cache.NoopCacheProvider(),
                    mock(ConfigurationService.class), events);

            assertThat(broken.getState(java.util.UUID.randomUUID()))
                    .as("промах и отказ обязаны различаться")
                    .isCompletedExceptionally();
        }

        @Test
        @DisplayName("привязка сессии при отказе не выдаёт успех")
        void привязкаСессииПриОтказеНеВыдаётУспех() {
            SessionServiceImpl broken = new SessionServiceImpl(repository,
                    mock(DeviceRepository.class),
                    new net.kofnetwork.auth.core.cache.NoopCacheProvider(),
                    mock(ConfigurationService.class), events);

            assertThat(broken.cacheForPlayer(java.util.UUID.randomUUID(), sessionWith("s")))
                    .isCompletedExceptionally();
        }
    }
}
