package net.kofnetwork.auth.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор охвата отзыва сессий из удалённого события.
 *
 * <p>Проверяется именно то место, где ошибка стоила дороже всего: пустой перечень
 * раньше означал «все сессии», и обычный вход — который отзывает прежнюю сессию и
 * называет её поимённо — выбрасывал из игры того, кто только что ввёл пароль.
 */
class KoFAuthVelocityTest {

    @Nested
    @DisplayName("явный охват")
    class ЯвныйОхват {

        @Test
        @DisplayName("SOME задевает только названные сессии")
        void some_задевает_только_названные() {
            Predicate<String> affects =
                    KoFAuthVelocity.revokedSessions("SOME", "старая-сессия", false);

            assertThat(affects.test("старая-сессия")).isTrue();
            assertThat(affects.test("новая-сессия")).isFalse();
        }

        @Test
        @DisplayName("ALL задевает любую сессию")
        void all_задевает_любую() {
            // Смена пароля и «выйти со всех устройств» приходят именно так.
            Predicate<String> affects = KoFAuthVelocity.revokedSessions("ALL", "", true);

            assertThat(affects.test("какая-угодно")).isTrue();
        }

        @Test
        @DisplayName("NONE не задевает никого")
        void none_не_задевает_никого() {
            // Ровно этот случай ломал вход: отзыв, которому нечего было отзывать,
            // приезжал с пустым перечнем и трактовался как «все».
            Predicate<String> affects = KoFAuthVelocity.revokedSessions("NONE", "", false);

            assertThat(affects.test("любая")).isFalse();
        }

        @Test
        @DisplayName("SOME с пустым перечнем никого не задевает")
        void some_с_пустым_перечнем_никого_не_задевает() {
            Predicate<String> affects = KoFAuthVelocity.revokedSessions("SOME", "", false);

            assertThat(affects.test("любая")).isFalse();
        }

        @Test
        @DisplayName("перечень из нескольких сессий разбирается целиком")
        void перечень_из_нескольких_сессий_разбирается_целиком() {
            Predicate<String> affects =
                    KoFAuthVelocity.revokedSessions("SOME", "первая, вторая ,третья", false);

            assertThat(affects.test("первая")).isTrue();
            assertThat(affects.test("вторая")).isTrue();
            assertThat(affects.test("третья")).isTrue();
            assertThat(affects.test("четвёртая")).isFalse();
        }

        @Test
        @DisplayName("неизвестный охват трактуется как NONE")
        void неизвестный_охват_трактуется_как_none() {
            // Событие от узла другой версии. Лишний отзыв выбрасывает играющих
            // людей, пропущенный — исправляется ближайшей сверкой состояния,
            // поэтому осторожный ответ здесь именно NONE.
            Predicate<String> affects =
                    KoFAuthVelocity.revokedSessions("НЕЧТО", "какая-то", false);

            assertThat(affects.test("какая-то")).isFalse();
        }
    }

    @Nested
    @DisplayName("узел прежней версии без поля scope")
    class БезОхвата {

        @Test
        @DisplayName("перечень сессий трактуется как SOME")
        void перечень_трактуется_как_some() {
            Predicate<String> affects = KoFAuthVelocity.revokedSessions("", "старая", false);

            assertThat(affects.test("старая")).isTrue();
            assertThat(affects.test("новая")).isFalse();
        }

        @Test
        @DisplayName("affectsAll остаётся признаком «все»")
        void affects_all_остаётся_признаком_всех() {
            Predicate<String> affects = KoFAuthVelocity.revokedSessions("", "", true);

            assertThat(affects.test("любая")).isTrue();
        }

        @Test
        @DisplayName("пустой перечень без affectsAll не задевает никого")
        void пустой_перечень_не_задевает_никого() {
            Predicate<String> affects = KoFAuthVelocity.revokedSessions("", "", false);

            assertThat(affects.test("любая")).isFalse();
        }
    }
}
