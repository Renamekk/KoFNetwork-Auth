package net.kofnetwork.auth.api.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthStateTest {

    @Test
    void только_AUTHENTICATED_считается_аутентифицированным() {
        assertThat(AuthState.AUTHENTICATED.isAuthenticated()).isTrue();
        for (AuthState state : AuthState.values()) {
            if (state != AuthState.AUTHENTICATED) {
                assertThat(state.isAuthenticated())
                        .as("состояние %s не должно считаться аутентифицированным", state)
                        .isFalse();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = AuthState.class, names = "AUTHENTICATED", mode = EnumSource.Mode.EXCLUDE)
    void все_кроме_AUTHENTICATED_удерживаются_в_лимбо(AuthState state) {
        assertThat(state.requiresLimbo()).isTrue();
    }

    @Test
    void аутентифицированный_игрок_в_лимбо_не_удерживается() {
        assertThat(AuthState.AUTHENTICATED.requiresLimbo()).isFalse();
    }

    @Test
    void нельзя_вернуться_назад_с_шага_капчи_на_ввод_пароля() {
        // Иначе прошедшего CAPTCHA игрока можно было бы отбросить на предыдущий шаг.
        assertThat(AuthState.CAPTCHA_REQUIRED.canTransitionTo(AuthState.AWAITING_LOGIN)).isFalse();
        assertThat(AuthState.TWO_FACTOR_REQUIRED.canTransitionTo(AuthState.CAPTCHA_REQUIRED)).isFalse();
    }

    @Test
    void из_терминальных_состояний_переходов_нет() {
        for (AuthState target : AuthState.values()) {
            if (target != AuthState.AUTHENTICATED) {
                assertThat(AuthState.AUTHENTICATED.canTransitionTo(target))
                        .as("AUTHENTICATED -> %s", target)
                        .isFalse();
            }
            if (target != AuthState.BLOCKED) {
                assertThat(AuthState.BLOCKED.canTransitionTo(target))
                        .as("BLOCKED -> %s", target)
                        .isFalse();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = AuthState.class, names = {"AUTHENTICATED", "BLOCKED"}, mode = EnumSource.Mode.EXCLUDE)
    void в_BLOCKED_можно_попасть_из_любого_нетерминального_состояния(AuthState from) {
        assertThat(from.canTransitionTo(AuthState.BLOCKED)).isTrue();
    }

    @Test
    void допускает_штатный_путь_входа() {
        assertThat(AuthState.CONNECTING.canTransitionTo(AuthState.AWAITING_LOGIN)).isTrue();
        assertThat(AuthState.AWAITING_LOGIN.canTransitionTo(AuthState.TWO_FACTOR_REQUIRED)).isTrue();
        assertThat(AuthState.TWO_FACTOR_REQUIRED.canTransitionTo(AuthState.AUTHENTICATED)).isTrue();
    }

    @Test
    void допускает_путь_регистрации_через_капчу() {
        assertThat(AuthState.CONNECTING.canTransitionTo(AuthState.AWAITING_REGISTER)).isTrue();
        assertThat(AuthState.AWAITING_REGISTER.canTransitionTo(AuthState.CAPTCHA_REQUIRED)).isTrue();
        assertThat(AuthState.CAPTCHA_REQUIRED.canTransitionTo(AuthState.AUTHENTICATED)).isTrue();
    }

    @Test
    void допускает_вход_с_валидной_сессией_минуя_ввод_пароля() {
        assertThat(AuthState.CONNECTING.canTransitionTo(AuthState.AUTHENTICATED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(AuthState.class)
    void переход_в_себя_допустим_всегда(AuthState state) {
        // Повторная установка того же состояния — обычное дело при переподключении.
        assertThat(state.canTransitionTo(state)).isTrue();
    }
}
