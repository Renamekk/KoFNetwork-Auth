package net.kofnetwork.auth.api.model;

/** Откуда пришло действие. Общий словарь для {@code security_logs} и {@code login_history}. */
public enum EventSource {
    MINECRAFT,
    WEB,
    TELEGRAM,
    DISCORD,
    API,

    /** Действие автоматики: планировщик, миграция, фоновая чистка. */
    SYSTEM
}
