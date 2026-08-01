package net.kofnetwork.auth.api.model;

/**
 * Важность события аудита. Соответствует {@code security_logs.severity}.
 *
 * <p>От значения зависит не только цвет строки в панели: записи уровня {@link #INFO}
 * ротируются через 90 дней, а {@link #WARNING} и {@link #CRITICAL} хранятся бессрочно.
 */
public enum Severity {

    /** Штатное событие: вход, выход, смена настройки уведомлений. */
    INFO,

    /** Требует внимания: неудачные входы, вход с нового устройства, смена пароля. */
    WARNING,

    /** Инцидент: подтверждённый перебор, повторное использование refresh-токена, блокировка. */
    CRITICAL;

    /** Уведомлять ли владельца аккаунта немедленно (Telegram, Discord, e-mail). */
    public boolean notifiesOwner() {
        return this != INFO;
    }
}
