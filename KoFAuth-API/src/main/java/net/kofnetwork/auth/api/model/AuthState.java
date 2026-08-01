package net.kofnetwork.auth.api.model;

/**
 * Состояние игрока в машине аутентификации.
 *
 * <p>Живёт в Redis по ключу {@code kofauth:authstate:{uuid}}, а не в памяти процесса:
 * иначе переключение игрока между Limbo-инстансами (или перезапуск одного из них)
 * сбрасывало бы прогресс входа, и игрок начинал бы заново.
 *
 * <p>Допустимые переходы описаны в {@link #canTransitionTo(AuthState)}; попытка
 * недопустимого перехода — признак ошибки в коде или гонки, и должна логироваться.
 */
public enum AuthState {

    /** Соединение установлено, состояние ещё не определено. */
    CONNECTING,

    /** Аккаунта нет — ждём {@code /register}. */
    AWAITING_REGISTER,

    /** Аккаунт есть — ждём {@code /login}. */
    AWAITING_LOGIN,

    /** Учётные данные приняты, требуется пройти CAPTCHA. */
    CAPTCHA_REQUIRED,

    /** Учётные данные приняты, требуется второй фактор. */
    TWO_FACTOR_REQUIRED,

    /** Вход завершён, сессия создана. */
    AUTHENTICATED,

    /** Доступ запрещён: AntiBot, бан, исчерпаны попытки. Далее только кик. */
    BLOCKED;

    /** Может ли игрок в этом состоянии взаимодействовать с миром. */
    public boolean isAuthenticated() {
        return this == AUTHENTICATED;
    }

    /** Должен ли игрок в этом состоянии удерживаться в Limbo. */
    public boolean requiresLimbo() {
        return this != AUTHENTICATED;
    }

    /**
     * Разрешён ли переход в целевое состояние.
     *
     * <p>Правило простое: из терминальных состояний ({@link #AUTHENTICATED}, {@link #BLOCKED})
     * выхода нет — сессия закончилась, следующая начнётся с {@link #CONNECTING}.
     * В {@link #BLOCKED} можно попасть откуда угодно. Назад по цепочке ходить нельзя,
     * иначе прошедший CAPTCHA игрок мог бы быть возвращён на шаг ввода пароля.
     */
    public boolean canTransitionTo(AuthState target) {
        if (this == target) {
            return true;
        }
        if (this == AUTHENTICATED || this == BLOCKED) {
            return false;
        }
        if (target == BLOCKED) {
            return true;
        }
        return switch (this) {
            case CONNECTING -> target != CONNECTING;
            case AWAITING_REGISTER, AWAITING_LOGIN ->
                    target == CAPTCHA_REQUIRED || target == TWO_FACTOR_REQUIRED || target == AUTHENTICATED;
            case CAPTCHA_REQUIRED -> target == TWO_FACTOR_REQUIRED || target == AUTHENTICATED;
            case TWO_FACTOR_REQUIRED -> target == AUTHENTICATED;
            default -> false;
        };
    }
}
