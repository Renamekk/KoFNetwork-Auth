package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.TotpSecret;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ к таблице {@code totp}.
 *
 * <p>Шифрование секрета выполняется внутри реализации: наружу и внутрь идёт открытое
 * Base32-значение, в базу — результат AES-256-GCM. Сервисный слой о ключе не знает.
 */
public interface TotpRepository {

    @NotNull CompletableFuture<TotpSecret> insert(@NotNull TotpSecret secret);

    @NotNull CompletableFuture<Optional<TotpSecret>> findByAccount(long accountId);

    /** Включает второй фактор после подтверждения кодом. */
    @NotNull CompletableFuture<Boolean> enable(long accountId, @NotNull Instant at, long counter);

    /** Полностью удаляет настройку TOTP. */
    @NotNull CompletableFuture<Boolean> deleteByAccount(long accountId);

    /**
     * Атомарно фиксирует использованное временное окно.
     *
     * <p>Условие {@code WHERE last_used_counter IS NULL OR last_used_counter < ?} делает
     * операцию защитой от повторного использования кода: два параллельных запроса с
     * одним и тем же шестизначным кодом пройдут только один раз, и второй получит
     * {@code false}. Без атомарности перехваченный код можно было бы использовать
     * дважды в пределах одного 30-секундного окна.
     *
     * @return {@code true}, если окно принято именно этим вызовом
     */
    @NotNull CompletableFuture<Boolean> compareAndSetCounter(long accountId, long counter);
}
