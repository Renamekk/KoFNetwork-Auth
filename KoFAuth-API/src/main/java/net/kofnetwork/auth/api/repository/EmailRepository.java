package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.EmailBinding;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Доступ к таблице {@code emails}. */
public interface EmailRepository {

    @NotNull CompletableFuture<EmailBinding> insert(@NotNull EmailBinding binding);

    /** Основной адрес аккаунта. */
    @NotNull CompletableFuture<Optional<EmailBinding>> findPrimary(long accountId);

    @NotNull CompletableFuture<List<EmailBinding>> findByAccount(long accountId);

    /**
     * Ищет привязку по нормализованному адресу.
     *
     * <p>Возвращает список, а не одну запись: один адрес может быть привязан к нескольким
     * аккаунтам, и это допустимо (семья за одним ящиком). Уникальность ограничена парой
     * «аккаунт + адрес».
     */
    @NotNull CompletableFuture<List<EmailBinding>> findByEmail(@NotNull String emailLower);

    /** Отмечает адрес подтверждённым. */
    @NotNull CompletableFuture<Boolean> markVerified(long bindingId, @NotNull Instant at);

    @NotNull CompletableFuture<Void> updateNotificationSettings(long bindingId,
                                                                boolean notifyLogin,
                                                                boolean notifySecurity,
                                                                boolean notifyNewsletter);

    /** Делает адрес основным, снимая флаг с прочих адресов аккаунта. */
    @NotNull CompletableFuture<Void> setPrimary(long accountId, long bindingId);

    @NotNull CompletableFuture<Boolean> delete(long bindingId);

    /** Сколько аккаунтов привязано к адресу — ограничение против массовой регистрации. */
    @NotNull CompletableFuture<Integer> countAccountsByEmail(@NotNull String emailLower);
}
