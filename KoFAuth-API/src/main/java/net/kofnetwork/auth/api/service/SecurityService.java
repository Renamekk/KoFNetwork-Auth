package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.IpAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Защитные механизмы: ограничение скорости, AntiBot, репутация адреса.
 *
 * <p>Собран в один сервис, потому что все три механизма отвечают на один вопрос —
 * «стоит ли вообще обрабатывать этот запрос» — и вызываются вместе в самом начале
 * обработки, до обращения к базе.
 */
public interface SecurityService {

    /**
     * Проверяет ограничение скорости.
     *
     * <p>Реализация использует скользящее окно в Redis, а не счётчик с фиксированным
     * периодом: при фиксированном окне лимит «10 в минуту» позволяет сделать 20 запросов
     * подряд на стыке двух минут.
     *
     * @param scope область: {@code login}, {@code register}, {@code totp}, {@code reset}
     * @param key   ключ: адрес или идентификатор аккаунта
     */
    @NotNull CompletableFuture<RateLimitVerdict> checkRateLimit(@NotNull String scope, @NotNull String key);

    /**
     * Проверяет ограничение и одновременно засчитывает попытку.
     *
     * <p>Раздельные «проверить» и «засчитать» дают окно, в котором параллельные запросы
     * проходят проверку до того, как хоть один был засчитан, — и лимит превышается.
     */
    @NotNull CompletableFuture<RateLimitVerdict> checkAndConsume(@NotNull String scope, @NotNull String key);

    /** Сбрасывает счётчик — например, после успешного входа. */
    @NotNull CompletableFuture<Void> resetRateLimit(@NotNull String scope, @NotNull String key);

    /**
     * Вердикт ограничения скорости.
     *
     * @param retryAfter сколько ждать до следующей попытки; {@code null}, если разрешено
     */
    record RateLimitVerdict(boolean allowed, int remaining, @Nullable Duration retryAfter) {

        public static @NotNull RateLimitVerdict allow(int remaining) {
            return new RateLimitVerdict(true, remaining, null);
        }

        public static @NotNull RateLimitVerdict deny(@NotNull Duration retryAfter) {
            return new RateLimitVerdict(false, 0, retryAfter);
        }
    }

    /**
     * Проверяет подключение на признаки автоматизированной атаки.
     *
     * <p>Учитывается частота подключений с адреса и его подсети, число аккаунтов,
     * зарегистрированных с адреса, и общая скорость подключений к сети.
     */
    @NotNull CompletableFuture<Boolean> isBotSuspected(@NotNull AuthContext context);

    /**
     * Определяет, является ли адрес VPN, прокси или узлом хостинга.
     *
     * <p>Вердикт кэшируется на 12 часов: внешние службы репутации ограничивают частоту
     * запросов, и обращаться к ним на каждом подключении невозможно. При недоступности
     * службы возвращается {@link IpReputation#UNKNOWN} — отказ внешнего сервиса не должен
     * закрывать вход всем игрокам.
     */
    @NotNull CompletableFuture<IpReputation> checkIpReputation(@NotNull IpAddress ip);

    /** Вердикт по репутации адреса. */
    enum IpReputation {
        /** Обычный пользовательский адрес. */
        CLEAN,
        /** VPN или прокси. */
        PROXY,
        /** Диапазон хостинг-провайдера или дата-центра. */
        HOSTING,
        /** Известен по спискам вредоносной активности. */
        MALICIOUS,
        /** Определить не удалось: служба недоступна или адрес приватный. */
        UNKNOWN;

        /** Блокировать ли подключение при включённой проверке. */
        public boolean shouldBlock() {
            return this == PROXY || this == HOSTING || this == MALICIOUS;
        }
    }

    /**
     * Требуется ли для этого входа усиленная проверка (CAPTCHA, обязательный второй фактор).
     *
     * <p>Учитывает историю неудач, новизну устройства и страны, вердикт по адресу.
     */
    @NotNull CompletableFuture<Boolean> requiresElevatedVerification(long accountId, @NotNull AuthContext context);

    /**
     * Проверяет и гасит одноразовый nonce.
     *
     * <p>Операция атомарная ({@code GETDEL}): повторное предъявление того же nonce
     * вернёт {@code false}. Без атомарности двойное нажатие кнопки «Подтвердить» в
     * Telegram создало бы две сессии.
     *
     * @return {@code true}, если nonce был действителен и погашен именно этим вызовом
     */
    @NotNull CompletableFuture<Boolean> consumeNonce(@NotNull String nonce);

    /** Регистрирует одноразовый nonce с указанным сроком жизни. */
    @NotNull CompletableFuture<Void> issueNonce(@NotNull String nonce, @NotNull Duration ttl);
}
