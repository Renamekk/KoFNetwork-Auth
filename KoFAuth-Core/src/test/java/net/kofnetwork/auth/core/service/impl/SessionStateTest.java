package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.api.repository.DeviceRepository;
import net.kofnetwork.auth.api.repository.SessionRepository;
import net.kofnetwork.auth.core.cache.NoopCacheProvider;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Состояние машины входа в {@link SessionServiceImpl}.
 *
 * <p>Проверяются именно те свойства, отсутствие которых выбрасывало вошедшего
 * игрока в Limbo и отключало его при вводе пароля: срок жизни записи и
 * возможность начать новое подключение с чистого состояния.
 */
class SessionStateTest {

    /** Кэш в памяти с наблюдаемым сроком жизни каждого ключа. */
    private static final class RecordingCache extends NoopCacheProvider {

        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Duration> ttls = new HashMap<>();

        @Override
        public @NotNull CompletableFuture<Optional<String>> get(@NotNull String key) {
            return CompletableFuture.completedFuture(Optional.ofNullable(values.get(key)));
        }

        @Override
        public @NotNull CompletableFuture<Void> set(@NotNull String key,
                                                    @NotNull String value,
                                                    @NotNull Duration ttl) {
            values.put(key, value);
            ttls.put(key, ttl);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NotNull CompletableFuture<Boolean> delete(@NotNull String key) {
            ttls.remove(key);
            return CompletableFuture.completedFuture(values.remove(key) != null);
        }
    }

    private RecordingCache cache;
    private SessionServiceImpl sessions;

    @BeforeEach
    void setUp() {
        cache = new RecordingCache();

        ConfigurationService config = mock(ConfigurationService.class);
        when(config.getDuration(any(ConfigFile.class), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> switch (invocation.<String>getArgument(1)) {
                    case "auth.session.ttl" -> Duration.ofHours(1);
                    case "auth.session.absolute-ttl" -> Duration.ofDays(7);
                    case "auth.login.timeout" -> Duration.ofSeconds(60);
                    default -> invocation.getArgument(2);
                });
        when(config.getBoolean(any(ConfigFile.class), anyString(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(config.getInt(any(ConfigFile.class), anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        sessions = new SessionServiceImpl(
                mock(SessionRepository.class),
                mock(DeviceRepository.class),
                cache,
                config,
                mock(EventBus.class));
    }

    private Duration ttlOf(UUID uuid) {
        return cache.ttls.get("authstate:" + uuid);
    }

    @Nested
    @DisplayName("Срок жизни записи о состоянии")
    class StateLifetime {

        /**
         * Регрессия. Раньше срок был общим и равнялся пяти минутам, поэтому через
         * пять минут игры запись исчезала, состояние читалось как CONNECTING,
         * и вошедший игрок терял чат, команды и уезжал в Limbo при первом же
         * переключении сервера.
         */
        @Test
        @DisplayName("вошедший игрок живёт столько же, сколько сессия")
        void authenticatedStateOutlivesFiveMinutes() {
            UUID player = UUID.randomUUID();

            sessions.resetState(player, AuthState.AUTHENTICATED).join();

            assertThat(ttlOf(player))
                    .as("состояние вошедшего игрока обязано жить не меньше сессии")
                    .isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("ожидание ввода переживает таймаут входа")
        void pendingStateOutlivesLoginTimeout() {
            UUID player = UUID.randomUUID();

            sessions.resetState(player, AuthState.AWAITING_LOGIN).join();

            assertThat(ttlOf(player)).isGreaterThan(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("BLOCKED не переживает попытку войти заново")
        void blockedStateIsShortLived() {
            UUID player = UUID.randomUUID();

            sessions.resetState(player, AuthState.BLOCKED).join();

            assertThat(ttlOf(player)).isLessThanOrEqualTo(Duration.ofMinutes(1));
        }
    }

    @Nested
    @DisplayName("Начало нового подключения")
    class ConnectionStart {

        /**
         * Регрессия на сообщённую ошибку: игрок, у которого истекла сессия,
         * попадал не в Limbo, а сразу на игровой сервер.
         *
         * <p>Причина была в том, что прокси переводил состояние через setState,
         * а из терминального AUTHENTICATED перехода нет. Запись о прошлом входе
         * переживает соединение, поэтому переход отвергался, состояние
         * оставалось AUTHENTICATED — и маршрутизация считала игрока вошедшим.
         */
        @Test
        @DisplayName("остаток прошлого входа не мешает потребовать пароль")
        void resetOverridesTerminalState() {
            UUID player = UUID.randomUUID();
            sessions.resetState(player, AuthState.AUTHENTICATED).join();

            sessions.resetState(player, AuthState.AWAITING_LOGIN).join();

            assertThat(sessions.getState(player).join()).isEqualTo(AuthState.AWAITING_LOGIN);
        }

        @Test
        @DisplayName("остаток BLOCKED не запирает следующее подключение")
        void resetOverridesBlocked() {
            UUID player = UUID.randomUUID();
            sessions.resetState(player, AuthState.BLOCKED).join();

            sessions.resetState(player, AuthState.AWAITING_LOGIN).join();

            assertThat(sessions.getState(player).join()).isEqualTo(AuthState.AWAITING_LOGIN);
        }

        /** Обычные переходы по-прежнему проверяются: reset — не замена setState. */
        @Test
        @DisplayName("setState по-прежнему отвергает недопустимый переход")
        void setStateStillGuardsTransitions() {
            UUID player = UUID.randomUUID();
            sessions.resetState(player, AuthState.AUTHENTICATED).join();

            assertThat(sessions.setState(player, AuthState.AWAITING_LOGIN).join()).isFalse();
            assertThat(sessions.getState(player).join()).isEqualTo(AuthState.AUTHENTICATED);
        }
    }

    @Nested
    @DisplayName("Привязка UUID к сессии")
    class PlayerBinding {

        @Test
        @DisplayName("отсутствует, пока игрок не вошёл")
        void absentBeforeLogin() {
            assertThat(sessions.currentPublicId(UUID.randomUUID()).join()).isEmpty();
        }

        @Test
        @DisplayName("без привязки сессия считается недействующей")
        void noBindingMeansNoSession() {
            assertThat(sessions.hasValidSession(UUID.randomUUID()).join()).isFalse();
        }
    }
}
