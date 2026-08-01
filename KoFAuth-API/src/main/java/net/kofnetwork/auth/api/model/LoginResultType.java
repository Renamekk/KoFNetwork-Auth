package net.kofnetwork.auth.api.model;

/**
 * Исход попытки входа. Пишется в {@code login_history.result}.
 *
 * <p>Значения намеренно подробнее того, что показывается игроку. Наружу уходит
 * одно и то же сообщение «неверный логин или пароль» для {@link #UNKNOWN_ACCOUNT} и
 * {@link #BAD_PASSWORD}: если различать их в ответе, форма входа превращается в
 * средство проверки существования ника. В журнале же различие необходимо —
 * поток {@link #UNKNOWN_ACCOUNT} с одного IP означает перебор имён, а поток
 * {@link #BAD_PASSWORD} по одному нику — перебор пароля, и реагировать на них надо по-разному.
 */
public enum LoginResultType {

    /** Вход выполнен. */
    SUCCESS(true),

    /** Требуется второй фактор; сессия ещё не создана. */
    TWO_FACTOR_REQUIRED(false),

    /** Требуется пройти CAPTCHA. */
    CAPTCHA_REQUIRED(false),

    /** Такого аккаунта нет. Наружу неотличимо от {@link #BAD_PASSWORD}. */
    UNKNOWN_ACCOUNT(false),

    /** Пароль не совпал. */
    BAD_PASSWORD(false),

    /** Аккаунт временно заблокирован после серии неудач. */
    TEMPORARILY_LOCKED(false),

    /** Аккаунт заблокирован администратором. */
    ACCOUNT_LOCKED(false),

    /** Аккаунт забанен. */
    ACCOUNT_BANNED(false),

    /** Превышен лимит попыток на IP или аккаунт. */
    RATE_LIMITED(false),

    /** Отклонено AntiBot. */
    BOT_DETECTED(false),

    /** Отклонено проверкой VPN/прокси. */
    PROXY_DETECTED(false),

    /** Второй фактор не подтверждён: неверный код или отказ в Telegram/Discord. */
    TWO_FACTOR_FAILED(false),

    /** Истёк отведённый на вход таймаут. */
    TIMEOUT(false),

    /** Внутренняя ошибка: недоступна база или кэш. */
    ERROR(false);

    private final boolean success;

    LoginResultType(boolean success) {
        this.success = success;
    }

    /** Завершился ли вход созданием сессии. */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Считается ли исход неудачной попыткой для счётчика {@code failed_login_attempts}.
     *
     * <p>Промежуточные состояния ({@link #TWO_FACTOR_REQUIRED}, {@link #CAPTCHA_REQUIRED})
     * не являются неудачей — иначе честный игрок с включённой 2FA заблокировал бы себя
     * за пять входов. Не считается неудачей и {@link #ERROR}: наказывать игрока за
     * недоступность нашей базы неправильно.
     */
    public boolean countsAsFailedAttempt() {
        return switch (this) {
            case BAD_PASSWORD, TWO_FACTOR_FAILED, UNKNOWN_ACCOUNT -> true;
            default -> false;
        };
    }
}
