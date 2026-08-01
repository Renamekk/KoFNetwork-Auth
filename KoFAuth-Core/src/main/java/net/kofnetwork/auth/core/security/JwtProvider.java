package net.kofnetwork.auth.core.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import net.kofnetwork.auth.api.exception.ConfigurationException;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.api.service.TokenService;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Выпуск и проверка access-токенов JWT.
 *
 * <p><b>Только access.</b> Refresh-токены — не JWT, а случайные значения, хранимые
 * хэшем в таблице {@code tokens}. Причина в том, что JWT нельзя отозвать: он
 * самодостаточен и действует до истечения срока. Для access это приемлемо — он
 * живёт минуты; для refresh, живущего месяц, это означало бы, что «выйти со всех
 * устройств» не работает.
 *
 * <p><b>Почему в токене есть {@code sid}.</b> Одной подписи мало: она подтверждает,
 * что токен выпустили мы, но не что сессия ещё жива. Идентификатор сессии позволяет
 * сверить её состояние в Redis и отклонить токен, выпущенный до смены пароля.
 * Проверка подписи дешёвая и отсекает подделки без обращения к хранилищу; сверка
 * сессии выполняется уже после неё.
 *
 * <p><b>Алгоритм — HMAC-SHA512.</b> Асимметричная подпись (RS256) нужна, когда
 * проверяющая сторона не должна уметь выпускать токены. Здесь и выпуск, и проверка
 * происходят внутри нашей же сети, общий секрет проще в эксплуатации, а HMAC заметно
 * быстрее RSA.
 */
public final class JwtProvider {

    private static final String ISSUER = "kofauth";

    /** Претензия с внешним идентификатором сессии. */
    private static final String CLAIM_SESSION_ID = "sid";

    /** Претензия с ником — избавляет от похода в базу ради отображения имени. */
    private static final String CLAIM_USERNAME = "usr";

    /** Минимальная длина секрета: 32 байта под HMAC-SHA512. */
    public static final int MIN_SECRET_BYTES = 32;

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final Duration accessLifetime;

    /**
     * @param secret         общий секрет подписи
     * @param accessLifetime срок жизни access-токена
     * @throws ConfigurationException если секрет пуст или слишком короток
     */
    public JwtProvider(@NotNull String secret, @NotNull Duration accessLifetime) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < MIN_SECRET_BYTES) {
            throw new ConfigurationException(
                    "Секрет JWT должен быть не короче " + MIN_SECRET_BYTES + " байт, получено "
                            + raw.length + ". Короткий секрет подбирается перебором, "
                            + "после чего можно выпустить токен от имени любого игрока.");
        }
        if (accessLifetime.isNegative() || accessLifetime.isZero()) {
            throw new ConfigurationException("Срок жизни access-токена должен быть положительным");
        }
        if (accessLifetime.compareTo(Duration.ofHours(1)) > 0) {
            throw new ConfigurationException(
                    "Срок жизни access-токена больше часа делает невозможным быстрый отзыв доступа: "
                            + "токен нельзя аннулировать до истечения. Задано " + accessLifetime);
        }
        this.algorithm = Algorithm.HMAC512(raw);
        this.accessLifetime = accessLifetime;
        this.verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                // Допуск на расхождение часов между узлами сети.
                .acceptLeeway(5)
                .build();
    }

    /**
     * Выпускает access-токен.
     *
     * @param sessionPublicId внешний идентификатор сессии, а не первичный ключ:
     *                        токен видит клиент, и по нему не должно быть видно,
     *                        сколько сессий выдано за всё время
     */
    public @NotNull String issueAccessToken(long accountId,
                                            @NotNull String username,
                                            @NotNull String sessionPublicId) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(Long.toString(accountId))
                .withClaim(CLAIM_USERNAME, username)
                .withClaim(CLAIM_SESSION_ID, sessionPublicId)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plus(accessLifetime)))
                .sign(algorithm);
    }

    /**
     * Проверяет подпись и срок действия токена.
     *
     * <p>Не проверяет, жива ли сессия — это делает вызывающий по
     * {@link TokenService.AccessClaims#sessionPublicId()}. Разделение намеренное:
     * подпись проверяется без обращения к хранилищу, и запросы с подделанным
     * токеном отсекаются, не создавая нагрузки на Redis.
     */
    public @NotNull OperationResult<TokenService.AccessClaims> verify(@NotNull String token) {
        try {
            DecodedJWT jwt = verifier.verify(token);

            String subject = jwt.getSubject();
            String username = jwt.getClaim(CLAIM_USERNAME).asString();
            String sessionId = jwt.getClaim(CLAIM_SESSION_ID).asString();

            if (subject == null || username == null || sessionId == null) {
                return OperationResult.fail("TOKEN_MALFORMED",
                        "В токене отсутствуют обязательные претензии");
            }

            long accountId;
            try {
                accountId = Long.parseLong(subject);
            } catch (NumberFormatException e) {
                return OperationResult.fail("TOKEN_MALFORMED", "Некорректный subject в токене");
            }

            Date expiresAt = jwt.getExpiresAt();
            return OperationResult.ok(new TokenService.AccessClaims(
                    accountId,
                    username,
                    sessionId,
                    expiresAt == null ? Instant.now() : expiresAt.toInstant()));
        } catch (JWTVerificationException e) {
            // Подделанная подпись и истёкший срок различаются в коде ошибки, но
            // наружу оба должны выглядеть как «нужно войти заново».
            String code = e.getMessage() != null && e.getMessage().contains("expired")
                    ? "TOKEN_EXPIRED"
                    : "TOKEN_INVALID";
            return OperationResult.fail(code, "Токен не прошёл проверку: " + e.getMessage());
        }
    }

    /** Настроенный срок жизни access-токена. */
    public @NotNull Duration accessLifetime() {
        return accessLifetime;
    }
}
