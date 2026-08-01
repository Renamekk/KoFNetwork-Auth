package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.model.AuthToken;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.TokenType;
import net.kofnetwork.auth.api.result.OperationResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Выпуск и проверка токенов: одноразовых кодов и пар JWT.
 *
 * <p>Разделение ответственности с {@link net.kofnetwork.auth.api.repository.TokenRepository}:
 * репозиторий хранит хэши, сервис владеет генерацией значений, их хэшированием и
 * правилами ротации.
 */
public interface TokenService {

    /**
     * Выпускает одноразовый токен.
     *
     * @return сырое значение — единственный раз, когда оно существует; в базу уходит
     *         только его SHA-256
     */
    @NotNull CompletableFuture<IssuedToken> issue(@Nullable Long accountId,
                                                  @NotNull TokenType type,
                                                  @Nullable IpAddress ip,
                                                  @Nullable Duration customLifetime);

    /**
     * Выпущенный токен.
     *
     * @param value сырое значение для передачи владельцу
     * @param token запись в базе
     */
    record IssuedToken(@NotNull String value, @NotNull AuthToken token) {

        /** Значение скрыто: объект не должен раскрывать токен через логирование. */
        @Override
        public String toString() {
            return "IssuedToken{type=" + token.type() + ", value=<redacted>}";
        }
    }

    /**
     * Проверяет и гасит одноразовый токен.
     *
     * <p>Проверка и гашение — одна атомарная операция. Иначе два параллельных запроса с
     * одним кодом сброса пароля оба пройдут проверку и оба сработают.
     */
    @NotNull CompletableFuture<OperationResult<AuthToken>> consume(@NotNull String rawValue,
                                                                   @NotNull TokenType expectedType,
                                                                   @Nullable IpAddress ip);

    /** Проверяет токен, не гася его. */
    @NotNull CompletableFuture<OperationResult<AuthToken>> peek(@NotNull String rawValue,
                                                                 @NotNull TokenType expectedType);

    // ------------------------------------------------------------------ JWT

    /**
     * Выпускает пару access/refresh для веб-сессии.
     *
     * <p>Access живёт минуты и не хранится в базе — он самодостаточен и проверяется
     * подписью. Refresh живёт недели, хранится хэшем и ротируется при каждом использовании.
     */
    @NotNull CompletableFuture<TokenPair> issueTokenPair(long accountId,
                                                          long sessionId,
                                                          @Nullable IpAddress ip);

    /**
     * Пара токенов веб-сессии.
     *
     * @param accessToken       JWT для заголовка {@code Authorization}
     * @param refreshToken      значение для обновления пары
     * @param accessExpiresIn   срок жизни access
     * @param refreshExpiresIn  срок жизни refresh
     */
    record TokenPair(@NotNull String accessToken,
                     @NotNull String refreshToken,
                     @NotNull Duration accessExpiresIn,
                     @NotNull Duration refreshExpiresIn) {

        @Override
        public String toString() {
            return "TokenPair{accessExpiresIn=" + accessExpiresIn
                    + ", refreshExpiresIn=" + refreshExpiresIn
                    + ", tokens=<redacted>}";
        }
    }

    /**
     * Обновляет пару по refresh-токену с ротацией.
     *
     * <p>Старый refresh гасится, выдаётся новый со ссылкой на предыдущий. Предъявление
     * уже погашенного токена означает утечку: вся цепочка отзывается, а по аккаунту
     * публикуется событие подозрительной активности.
     */
    @NotNull CompletableFuture<OperationResult<TokenPair>> refresh(@NotNull String refreshToken,
                                                                    @Nullable IpAddress ip);

    /**
     * Проверяет access-токен.
     *
     * @return полезная нагрузка при валидной подписи и неистёкшем сроке
     */
    @NotNull CompletableFuture<OperationResult<AccessClaims>> verifyAccessToken(@NotNull String accessToken);

    /**
     * Полезная нагрузка access-токена.
     *
     * @param sessionPublicId внешний идентификатор сессии; по нему проверяется, что
     *                        сессия не отозвана — подпись сама по себе этого не гарантирует
     */
    record AccessClaims(long accountId,
                        @NotNull String username,
                        @NotNull String sessionPublicId,
                        @NotNull java.time.Instant expiresAt) {
    }

    /** Отзывает refresh-токен и связанную с ним сессию. */
    @NotNull CompletableFuture<OperationResult<Void>> revokeRefreshToken(@NotNull String refreshToken);

    /**
     * Отзывает все refresh-токены аккаунта.
     *
     * <p>Вызывается при смене пароля. Без этого веб-сессия, у которой есть
     * действующий refresh, продолжила бы обновляться сама и пережила бы смену
     * пароля — то есть «выйти со всех устройств» не сработало бы там, где это
     * нужнее всего.
     *
     * @return число отозванных токенов
     */
    @NotNull CompletableFuture<Integer> revokeRefreshTokensOf(long accountId);

    /**
     * Отзывает все действующие токены аккаунта указанного типа.
     *
     * <p>Вызывается перед выпуском нового одноразового кода. Иначе действующими
     * окажутся сразу несколько кодов сброса пароля, и каждый расширяет окно, в
     * течение которого доступ к почтовому ящику даёт доступ к аккаунту.
     *
     * @return число отозванных токенов
     */
    @NotNull CompletableFuture<Integer> revokeAllByType(long accountId, @NotNull TokenType type);
}
