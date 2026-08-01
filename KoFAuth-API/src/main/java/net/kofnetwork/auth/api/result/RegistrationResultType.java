package net.kofnetwork.auth.api.result;

/** Исход попытки регистрации. */
public enum RegistrationResultType {

    /** Аккаунт создан. */
    SUCCESS(true),

    /** Ник уже занят. */
    USERNAME_TAKEN(false),

    /** Ник не проходит проверку формата: длина, недопустимые символы, чёрный список. */
    INVALID_USERNAME(false),

    /** Пароль не удовлетворяет политике сложности. */
    PASSWORD_TOO_WEAK(false),

    /** Введённые пароли не совпали. */
    PASSWORDS_DO_NOT_MATCH(false),

    /** Регистрация выключена администратором. */
    REGISTRATION_DISABLED(false),

    /** Превышен лимит регистраций с этого IP. */
    IP_LIMIT_REACHED(false),

    /** Превышен лимит запросов. */
    RATE_LIMITED(false),

    /** Требуется пройти CAPTCHA до создания аккаунта. */
    CAPTCHA_REQUIRED(false),

    /** Отклонено AntiBot. */
    BOT_DETECTED(false),

    /** Отклонено проверкой VPN/прокси. */
    PROXY_DETECTED(false),

    /** Внутренняя ошибка. */
    ERROR(false);

    private final boolean success;

    RegistrationResultType(boolean success) {
        this.success = success;
    }

    public boolean isSuccess() {
        return success;
    }
}
