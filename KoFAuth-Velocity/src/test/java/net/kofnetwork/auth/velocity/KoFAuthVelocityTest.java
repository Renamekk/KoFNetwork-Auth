package net.kofnetwork.auth.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Разбор перечня отозванных сессий из удалённого события. */
class KoFAuthVelocityTest {

    @Test
    @DisplayName("отзыв чужой сессии не задевает текущую")
    void отзыв_чужой_сессии_не_задевает_текущую() {
        // Ровно то, что происходит при обычном входе: max-concurrent = 1,
        // успешный /login отзывает прежнюю сессию и называет её поимённо.
        // Раньше прокси не читал этот список и выбрасывал того самого игрока,
        // который только что ввёл пароль.
        Predicate<String> affects = KoFAuthVelocity.revokedSessions("старая-сессия", false);

        assertThat(affects.test("старая-сессия")).isTrue();
        assertThat(affects.test("новая-сессия")).isFalse();
    }

    @Test
    @DisplayName("отзыв всех сессий задевает любую")
    void отзыв_всех_сессий_задевает_любую() {
        // Смена пароля и «выйти со всех устройств» приходят именно так.
        Predicate<String> affects = KoFAuthVelocity.revokedSessions("", true);

        assertThat(affects.test("какая-угодно")).isTrue();
    }

    @Test
    @DisplayName("пустой перечень без affectsAll не задевает никого")
    void пустой_перечень_не_задевает_никого() {
        // Иначе событие, не касающееся ни одной сессии, выбрасывало бы
        // с сервера всех игроков аккаунта.
        Predicate<String> affects = KoFAuthVelocity.revokedSessions("", false);

        assertThat(affects.test("любая")).isFalse();
    }

    @Test
    @DisplayName("перечень из нескольких сессий разбирается целиком")
    void перечень_из_нескольких_сессий_разбирается_целиком() {
        Predicate<String> affects =
                KoFAuthVelocity.revokedSessions("первая, вторая ,третья", false);

        assertThat(affects.test("первая")).isTrue();
        assertThat(affects.test("вторая")).isTrue();
        assertThat(affects.test("третья")).isTrue();
        assertThat(affects.test("четвёртая")).isFalse();
    }
}
