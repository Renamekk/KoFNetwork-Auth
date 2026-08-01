package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Настройка TOTP (Google Authenticator). Соответствует строке {@code totp}.
 *
 * <p><b>Секрет.</b> {@link #secret()} — уже расшифрованное Base32-значение. В базе оно
 * лежит зашифрованным AES-256-GCM; шифрование и расшифровка выполняются в репозитории,
 * так что сервисный слой работает с обычной строкой, а дамп базы без ключа бесполезен.
 *
 * <p><b>Двухшаговое включение.</b> Запись создаётся с {@code enabled == false}: секрет
 * сгенерирован и показан игроку, но второй фактор ещё не работает. Флаг поднимается
 * только после того, как игрок ввёл верный код с этого секрета. Иначе можно запереть
 * себя, отсканировав QR с ошибкой.
 *
 * <p><b>Анти-replay.</b> {@link #lastUsedCounter()} хранит номер временного окна последнего
 * принятого кода. Тот же код второй раз не принимается: перехваченный из чата или
 * подсмотренный код бесполезен уже через мгновение.
 */
public record TotpSecret(
        long id,
        long accountId,
        @NotNull String secret,
        @NotNull String algorithm,
        int digits,
        int periodSeconds,
        boolean enabled,
        @Nullable Instant confirmedAt,
        @Nullable Long lastUsedCounter,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt
) {

    /** Алгоритм по умолчанию. SHA-1 — не выбор, а требование совместимости с приложениями. */
    public static final String ALGORITHM_SHA1 = "SHA1";
    /** Стандартное число цифр в коде. */
    public static final int DEFAULT_DIGITS = 6;
    /** Стандартная длина временного окна. */
    public static final int DEFAULT_PERIOD_SECONDS = 30;

    public TotpSecret {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (digits < 6 || digits > 8) {
            throw new IllegalArgumentException("Число цифр TOTP должно быть в диапазоне 6..8, получено " + digits);
        }
        if (periodSeconds < 15 || periodSeconds > 120) {
            throw new IllegalArgumentException(
                    "Период TOTP должен быть в диапазоне 15..120 секунд, получено " + periodSeconds);
        }
    }

    /**
     * Создаёт неподтверждённую настройку со стандартными параметрами.
     *
     * <p>Параметры именно стандартные, а не «усиленные»: Google Authenticator, Authy и
     * большинство прочих приложений игнорируют алгоритм и период из ссылки otpauth и
     * считают код по SHA-1/6/30. Отклонение от этих значений на практике означает, что
     * у части игроков коды просто не будут сходиться.
     */
    public static @NotNull TotpSecret pending(long accountId, @NotNull String secret) {
        Instant now = Instant.now();
        return new TotpSecret(0L, accountId, secret, ALGORITHM_SHA1,
                DEFAULT_DIGITS, DEFAULT_PERIOD_SECONDS, false, null, null, now, now);
    }

    /** Включает второй фактор после подтверждения кодом. */
    public @NotNull TotpSecret confirm(@NotNull Instant at, long counter) {
        return new TotpSecret(id, accountId, secret, algorithm, digits, periodSeconds,
                true, at, counter, createdAt, at);
    }

    /** Фиксирует использованное временное окно. */
    public @NotNull TotpSecret withUsedCounter(long counter) {
        return new TotpSecret(id, accountId, secret, algorithm, digits, periodSeconds,
                enabled, confirmedAt, counter, createdAt, Instant.now());
    }

    public @NotNull TotpSecret withId(long newId) {
        return new TotpSecret(newId, accountId, secret, algorithm, digits, periodSeconds,
                enabled, confirmedAt, lastUsedCounter, createdAt, updatedAt);
    }

    /**
     * Не использовалось ли это временное окно ранее.
     *
     * @param counter номер окна проверяемого кода
     */
    public boolean isCounterFresh(long counter) {
        return lastUsedCounter == null || counter > lastUsedCounter;
    }

    /** Работает ли второй фактор прямо сейчас. */
    public boolean isActive() {
        return enabled && confirmedAt != null;
    }

    /**
     * Ссылка {@code otpauth://} для QR-кода.
     *
     * @param issuer      имя сети, отображаемое в приложении
     * @param accountName имя аккаунта, отображаемое в приложении
     */
    public @NotNull String toUri(@NotNull String issuer, @NotNull String accountName) {
        String encodedIssuer = urlEncode(issuer);
        String encodedAccount = urlEncode(accountName);
        return "otpauth://totp/" + encodedIssuer + ":" + encodedAccount
                + "?secret=" + secret
                + "&issuer=" + encodedIssuer
                + "&algorithm=" + algorithm
                + "&digits=" + digits
                + "&period=" + periodSeconds;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** Секрет заменён заглушкой: {@code toString()} не должен раскрывать второй фактор. */
    @Override
    public String toString() {
        return "TotpSecret{id=" + id
                + ", accountId=" + accountId
                + ", enabled=" + enabled
                + ", algorithm=" + algorithm
                + ", digits=" + digits
                + ", secret=<redacted>}";
    }
}
