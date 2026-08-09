package net.kofnetwork.auth.velocity.listener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Учёт входа: кто освобождён от отключения по таймауту и какой попытке
 * принадлежит решение владельца.
 *
 * <p>Регрессия на гонку: раньше решение об отключении принималось по двум
 * независимым величинам — моменту подключения и состоянию в общем хранилище, —
 * и между их чтением и вызовом {@code disconnect()} помещался успешный вход.
 * Игрок вводил верный пароль, начинал переходить на хаб и в этот момент
 * получал «Вы не успели войти».
 */
class PendingLoginsTest {

    private PendingLogins logins;
    private UUID player;

    @BeforeEach
    void setUp() {
        logins = new PendingLogins();
        player = UUID.randomUUID();
        logins.connected(player);
    }

    @Nested
    @DisplayName("Освобождение от таймаута")
    class TimeoutExemption {

        @Test
        @DisplayName("только что подключившийся под таймаут подпадает")
        void толькоЧтоПодключившийсяПодпадает() {
            assertThat(logins.isExemptFromTimeout(player, Instant.now())).isFalse();
        }

        /**
         * Отметка ставится <em>до</em> начала перевода на хаб — именно в этом
         * промежутке и происходило ошибочное отключение.
         */
        @Test
        @DisplayName("вошедший игрок освобождён немедленно")
        void вошедшийОсвобождёнНемедленно() {
            logins.completed(player);

            assertThat(logins.isExemptFromTimeout(player, Instant.now())).isTrue();
        }

        @Test
        @DisplayName("ожидание кнопки продлевает срок, но не навсегда")
        void ожиданиеКнопкиПродлеваетСрок() {
            Instant now = Instant.now();
            logins.awaitingApproval(player, "попытка-1", Duration.ofMinutes(2));

            assertThat(logins.isExemptFromTimeout(player, now)).isTrue();
            assertThat(logins.isExemptFromTimeout(player, now.plusSeconds(180)))
                    .as("не дождавшийся решения игрок обязан быть отключён")
                    .isFalse();
        }

        @Test
        @DisplayName("подтверждение после ожидания освобождает окончательно")
        void подтверждениеПослеОжиданияОсвобождает() {
            logins.awaitingApproval(player, "попытка-1", Duration.ofSeconds(1));
            logins.completed(player);

            assertThat(logins.isExemptFromTimeout(player, Instant.now().plusSeconds(600)))
                    .isTrue();
        }

        @Test
        @DisplayName("сброс возвращает игрока под таймаут")
        void сбросВозвращаетПодТаймаут() {
            logins.completed(player);
            logins.reset(player);

            assertThat(logins.isExemptFromTimeout(player, Instant.now())).isFalse();
        }

        @Test
        @DisplayName("новое подключение начинает отсчёт заново")
        void новоеПодключениеНачинаетОтсчётЗаново() {
            logins.completed(player);
            logins.connected(player);

            assertThat(logins.isExemptFromTimeout(player, Instant.now())).isFalse();
        }

        @Test
        @DisplayName("отключение забывает игрока")
        void отключениеЗабываетИгрока() {
            logins.completed(player);
            logins.disconnected(player);

            assertThat(logins.isKnown(player)).isFalse();
            assertThat(logins.isExemptFromTimeout(player, Instant.now())).isFalse();
        }
    }

    @Nested
    @DisplayName("Сопоставление попытки")
    class AttemptMatching {

        @Test
        @DisplayName("решение относится к текущей попытке")
        void решениеОтноситсяКТекущейПопытке() {
            logins.awaitingApproval(player, "попытка-1", Duration.ofMinutes(2));

            assertThat(logins.matchesAttempt(player, "попытка-1")).isTrue();
        }

        /**
         * Повторный {@code /login} начинает новую попытку. Нажатие кнопки от
         * прежней не должно ни пускать игрока, ни выбрасывать его.
         */
        @Test
        @DisplayName("решение по прежней попытке отбрасывается")
        void решениеПоПрежнейПопыткеОтбрасывается() {
            logins.awaitingApproval(player, "попытка-1", Duration.ofMinutes(2));
            logins.awaitingApproval(player, "попытка-2", Duration.ofMinutes(2));

            assertThat(logins.matchesAttempt(player, "попытка-1")).isFalse();
            assertThat(logins.matchesAttempt(player, "попытка-2")).isTrue();
        }

        @Test
        @DisplayName("без попытки не совпадает ничто")
        void безПопыткиНеСовпадаетНичто() {
            assertThat(logins.matchesAttempt(player, "попытка-1")).isFalse();
            assertThat(logins.attemptId(player)).isEmpty();
        }

        @Test
        @DisplayName("чужой игрок не наследует чужую попытку")
        void чужойИгрокНеНаследуетПопытку() {
            logins.awaitingApproval(player, "попытка-1", Duration.ofMinutes(2));

            assertThat(logins.matchesAttempt(UUID.randomUUID(), "попытка-1")).isFalse();
        }
    }
}
