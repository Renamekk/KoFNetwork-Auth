package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.model.LoginAttempt;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Доступ к таблице {@code login_history}. */
public interface LoginHistoryRepository {

    /**
     * Записывает попытку входа.
     *
     * <p>Вызывается вне критического пути: игроку уже ответили, и ожидание записи в
     * журнал только увеличило бы задержку. Сбой записи не должен отменять успешный вход —
     * вызывающий код логирует исключение и продолжает.
     */
    @NotNull CompletableFuture<LoginAttempt> insert(@NotNull LoginAttempt attempt);

    /** История входов аккаунта от новых к старым. */
    @NotNull CompletableFuture<List<LoginAttempt>> findByAccount(long accountId, int limit, int offset);

    /** Все попытки с адреса за период. Основной инструмент расследования инцидентов. */
    @NotNull CompletableFuture<List<LoginAttempt>> findByIp(@NotNull IpAddress ip,
                                                            @NotNull Instant since,
                                                            int limit);

    /**
     * Число неудачных попыток по аккаунту за период.
     * Основа для решения о временной блокировке.
     */
    @NotNull CompletableFuture<Integer> countFailedSince(long accountId, @NotNull Instant since);

    /**
     * Число неудачных попыток с адреса за период.
     *
     * <p>Считается отдельно от счётчика по аккаунту: перебор паролей к одному аккаунту и
     * перебор аккаунтов с одного адреса — разные атаки, и блокировать в них нужно разные
     * сущности. Блокировка только по аккаунту позволяет атакующему бесконечно перебирать
     * ники; блокировка только по IP запирает всех за одним NAT.
     */
    @NotNull CompletableFuture<Integer> countFailedFromIpSince(@NotNull IpAddress ip, @NotNull Instant since);

    /** Отличается ли страна последнего входа от указанной — признак подозрительного входа. */
    @NotNull CompletableFuture<Boolean> hasSuccessfulLoginFromCountry(long accountId, @NotNull String country);

    /** Последний успешный вход аккаунта. */
    @NotNull CompletableFuture<java.util.Optional<LoginAttempt>> findLastSuccessful(long accountId);

    /** Удаляет записи старше указанного момента. */
    @NotNull CompletableFuture<Integer> deleteBefore(@NotNull Instant before);
}
