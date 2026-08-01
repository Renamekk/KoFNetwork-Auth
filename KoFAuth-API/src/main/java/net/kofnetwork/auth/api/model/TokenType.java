package net.kofnetwork.auth.api.model;

import java.time.Duration;

/**
 * Назначение токена. Соответствует {@code tokens.type}.
 *
 * <p>Срок жизни и одноразовость заданы прямо здесь, а не в конфигурации: это
 * характеристики протокола, а не настройки вкуса. Возможность выставить
 * {@code PASSWORD_RESET} сроком в 30 дней — это возможность выстрелить себе в ногу.
 */
public enum TokenType {

    /** Refresh-токен веб-сессии. Долгоживущий, но ротируется при каждом использовании. */
    REFRESH(Duration.ofDays(30), true),

    /** Код подтверждения e-mail. */
    EMAIL_VERIFY(Duration.ofHours(24), true),

    /** Код восстановления пароля. Короткий срок — окно для атаки на почтовый ящик. */
    PASSWORD_RESET(Duration.ofMinutes(30), true),

    /** Код привязки Telegram: игрок вводит его боту. */
    TELEGRAM_LINK(Duration.ofMinutes(15), true),

    /** Код привязки Discord. */
    DISCORD_LINK(Duration.ofMinutes(15), true),

    /** Запрос подтверждения входа: живёт ровно столько, сколько игрок ждёт в Limbo. */
    LOGIN_APPROVAL(Duration.ofMinutes(2), true),

    /**
     * Резервный код TOTP. Формально бессрочен — срок задаётся датой далеко в будущем,
     * потому что выданный игроку листок с кодами не должен «протухать» сам по себе.
     */
    TOTP_RECOVERY(Duration.ofDays(3650), true),

    /** Ключ машинного доступа к REST API. Многоразовый. */
    API_KEY(Duration.ofDays(365), false);

    private final Duration defaultLifetime;
    private final boolean singleUse;

    TokenType(Duration defaultLifetime, boolean singleUse) {
        this.defaultLifetime = defaultLifetime;
        this.singleUse = singleUse;
    }

    public Duration defaultLifetime() {
        return defaultLifetime;
    }

    /** Сгорает ли токен после первого предъявления. */
    public boolean isSingleUse() {
        return singleUse;
    }

    /**
     * Участвует ли тип в цепочке ротации ({@code parent_token_id}).
     *
     * <p>Только refresh: предъявление уже использованного звена цепочки означает, что
     * токен утёк, и вся цепочка отзывается разом.
     */
    public boolean isRotating() {
        return this == REFRESH;
    }
}
