package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.AuthToken;
import net.kofnetwork.auth.api.model.TokenType;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ к таблице {@code tokens}.
 *
 * <p>Все методы принимают и возвращают <b>хэш</b> токена, а не его значение. Сырой токен
 * существует ровно один раз — в момент выпуска, когда он уходит игроку. Репозиторий
 * его не видит.
 */
public interface TokenRepository {

    @NotNull CompletableFuture<AuthToken> insert(@NotNull AuthToken token);

    /** Находит токен по SHA-256 предъявленного значения. */
    @NotNull CompletableFuture<Optional<AuthToken>> findByHash(@NotNull String tokenHash);

    @NotNull CompletableFuture<Optional<AuthToken>> findById(long id);

    /**
     * Атомарно помечает токен использованным.
     *
     * <p>Возвращает {@code false}, если токен уже был использован. Это ключевая операция
     * для одноразовых кодов: проверка «не использован ли» и пометка обязаны быть одним
     * действием ({@code UPDATE ... WHERE used = 0}), иначе два одновременных запроса
     * с одним кодом восстановления пароля оба пройдут проверку и оба сработают.
     *
     * @return {@code true}, если пометка выполнена именно этим вызовом
     */
    @NotNull CompletableFuture<Boolean> markUsed(long tokenId,
                                                 @NotNull Instant at,
                                                 @org.jetbrains.annotations.Nullable
                                                 net.kofnetwork.auth.api.model.IpAddress ip);

    /**
     * Погашает одноразовый токен целиком одной атомарной операцией.
     *
     * <p>Проверка «нужный тип, не использован, не отозван, не истёк» и пометка
     * использованным выполняются одним {@code UPDATE ... WHERE}, а не последовательностью
     * «прочитать, проверить, записать». Прежний порядок оставлял окно между чтением и
     * пометкой: две одновременные попытки погасить один код обе проходили проверку,
     * и второй запрос успевал воспользоваться токеном, который первый уже считал своим.
     * Особенно заметно это было на двойном нажатии кнопки подтверждения входа.
     *
     * <p>Причина отказа определяется <em>после</em> неудачной попытки, отдельным чтением.
     * Такое чтение ничего не решает — решение уже принято {@code UPDATE}, — и служит
     * только тому, чтобы вызывающий мог отличить «истёк» от «уже использован».
     *
     * @return исход попытки; {@link ConsumeOutcome#token()} заполнен при успехе
     *         и при объяснимом отказе
     */
    @NotNull CompletableFuture<ConsumeOutcome> consumeByHash(@NotNull String tokenHash,
                                                             @NotNull TokenType expectedType,
                                                             @NotNull Instant at,
                                                             @org.jetbrains.annotations.Nullable
                                                             net.kofnetwork.auth.api.model.IpAddress ip);

    /** Исход атомарного погашения токена. */
    enum ConsumeStatus {

        /** Токен погашен именно этим вызовом. */
        CONSUMED,

        /** Токена с таким хэшем нет. */
        NOT_FOUND,

        /** Токен есть, но предназначен для другой операции. */
        WRONG_TYPE,

        /** Токен отозван. */
        REVOKED,

        /** Срок действия истёк. */
        EXPIRED,

        /** Токен уже был использован — либо повтор, либо утечка. */
        ALREADY_USED
    }

    /**
     * @param status исход
     * @param token  строка токена, если её удалось прочитать; при {@link ConsumeStatus#CONSUMED}
     *               содержит уже погашенное состояние
     */
    record ConsumeOutcome(@NotNull ConsumeStatus status,
                          @org.jetbrains.annotations.Nullable AuthToken token) {

        public boolean isConsumed() {
            return status == ConsumeStatus.CONSUMED;
        }
    }

    @NotNull CompletableFuture<Boolean> revoke(long tokenId, @NotNull Instant at);

    /**
     * Отзывает всю цепочку ротации, к которой принадлежит токен: и предков, и потомков.
     *
     * <p>Вызывается при обнаружении повторного использования refresh-токена. Раз одно
     * звено предъявлено дважды, цепочка скомпрометирована целиком, и выяснять, какая
     * из сторон настоящая, поздно.
     *
     * @return число отозванных токенов
     */
    @NotNull CompletableFuture<Integer> revokeChain(long tokenId, @NotNull Instant at);

    /** Отзывает все действующие токены аккаунта указанного типа. */
    @NotNull CompletableFuture<Integer> revokeAllByAccountAndType(long accountId,
                                                                  @NotNull TokenType type,
                                                                  @NotNull Instant at);

    /** Действующие токены аккаунта указанного типа. */
    @NotNull CompletableFuture<List<AuthToken>> findUsableByAccountAndType(long accountId,
                                                                           @NotNull TokenType type,
                                                                           @NotNull Instant at);

    /** Число неиспользованных резервных кодов TOTP. */
    @NotNull CompletableFuture<Integer> countUsable(long accountId,
                                                    @NotNull TokenType type,
                                                    @NotNull Instant at);

    /** Удаляет отработавшие токены. Вызывается планировщиком. */
    @NotNull CompletableFuture<Integer> deleteExpiredAndUsed(@NotNull Instant before);
}
