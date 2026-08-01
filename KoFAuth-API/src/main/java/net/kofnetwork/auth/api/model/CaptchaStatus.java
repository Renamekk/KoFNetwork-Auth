package net.kofnetwork.auth.api.model;

/** Состояние выданного CAPTCHA-челленджа. Соответствует {@code captcha.status}. */
public enum CaptchaStatus {

    /** Выдан, ответ ещё не получен. */
    PENDING,

    /** Решён верно. */
    PASSED,

    /** Исчерпаны попытки. */
    FAILED,

    /** Истёк срок жизни. */
    EXPIRED;

    /** Завершён ли челлендж (в любом исходе). */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
