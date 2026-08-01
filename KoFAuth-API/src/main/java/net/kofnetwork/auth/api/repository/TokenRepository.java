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
