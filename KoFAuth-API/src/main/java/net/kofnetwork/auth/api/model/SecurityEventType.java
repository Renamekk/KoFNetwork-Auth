package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;

/**
 * Словарь событий аудита.
 *
 * <p>В базе колонка {@code security_logs.event_type} — {@code VARCHAR}, а не {@code ENUM}:
 * добавление события не должно требовать миграции схемы. Этот перечень — соглашение
 * на стороне кода, задающее канонические имена и уровень важности по умолчанию.
 */
public enum SecurityEventType {

    // --- Жизненный цикл аккаунта ---
    ACCOUNT_REGISTERED(Severity.INFO),
    ACCOUNT_DELETED(Severity.CRITICAL),
    ACCOUNT_LOCKED(Severity.CRITICAL),
    ACCOUNT_UNLOCKED(Severity.WARNING),

    // --- Аутентификация ---
    LOGIN_SUCCESS(Severity.INFO),
    LOGIN_FAILED(Severity.WARNING),
    LOGIN_FROM_NEW_DEVICE(Severity.WARNING),
    LOGIN_FROM_NEW_COUNTRY(Severity.WARNING),
    LOGOUT(Severity.INFO),

    // --- Учётные данные ---
    PASSWORD_CHANGED(Severity.WARNING),
    PASSWORD_RESET_REQUESTED(Severity.WARNING),
    PASSWORD_RESET_COMPLETED(Severity.WARNING),

    // --- Привязки ---
    EMAIL_LINKED(Severity.WARNING),
    EMAIL_UNLINKED(Severity.WARNING),
    EMAIL_VERIFIED(Severity.INFO),
    TELEGRAM_LINKED(Severity.WARNING),
    TELEGRAM_UNLINKED(Severity.WARNING),
    DISCORD_LINKED(Severity.WARNING),
    DISCORD_UNLINKED(Severity.WARNING),

    // --- Второй фактор ---
    TOTP_ENABLED(Severity.WARNING),
    TOTP_DISABLED(Severity.CRITICAL),
    TOTP_FAILED(Severity.WARNING),
    RECOVERY_CODE_USED(Severity.CRITICAL),
    RECOVERY_CODES_REGENERATED(Severity.WARNING),

    // --- Сессии и устройства ---
    SESSION_CREATED(Severity.INFO),
    SESSION_REVOKED(Severity.INFO),
    ALL_SESSIONS_REVOKED(Severity.WARNING),
    DEVICE_TRUSTED(Severity.WARNING),
    DEVICE_REMOVED(Severity.WARNING),

    // --- Защита ---
    CAPTCHA_PASSED(Severity.INFO),
    CAPTCHA_FAILED(Severity.WARNING),
    RATE_LIMIT_EXCEEDED(Severity.WARNING),
    BOT_DETECTED(Severity.CRITICAL),
    PROXY_DETECTED(Severity.WARNING),
    BRUTE_FORCE_DETECTED(Severity.CRITICAL),
    TOKEN_REPLAY_DETECTED(Severity.CRITICAL),
    SESSION_IP_MISMATCH(Severity.CRITICAL),

    // --- Администрирование ---
    ADMIN_ACTION(Severity.WARNING),
    CONFIG_RELOADED(Severity.INFO),
    DATA_EXPORTED(Severity.CRITICAL),
    DATA_IMPORTED(Severity.CRITICAL);

    private final Severity defaultSeverity;

    SecurityEventType(Severity defaultSeverity) {
        this.defaultSeverity = defaultSeverity;
    }

    public @NotNull Severity defaultSeverity() {
        return defaultSeverity;
    }
}
