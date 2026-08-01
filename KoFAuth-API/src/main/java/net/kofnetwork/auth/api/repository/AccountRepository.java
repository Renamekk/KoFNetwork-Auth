package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.AccountStatus;
import net.kofnetwork.auth.api.model.IpAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ к таблице {@code users}.
 *
 * <p><b>Всё асинхронно.</b> Каждый метод возвращает {@link CompletableFuture} и выполняется
 * на пуле {@code kofauth-db}. Это не стилистическое предпочтение: обращение к MySQL из
 * главного потока Minecraft останавливает тик всего сервера, и при 5 000 игроков одна
 * синхронная выборка на входе превращается в заметный фриз для всех.
 *
 * <p><b>Обработка ошибок.</b> Сбой базы приводит к завершению future исключением
 * {@link net.kofnetwork.auth.api.exception.RepositoryException}. Отсутствие записи
 * ошибкой не является и выражается пустым {@link Optional}.
 */
public interface AccountRepository {

    /**
     * Ищет аккаунт по нику без учёта регистра.
     *
     * <p>Самый горячий запрос системы: выполняется на каждом подключении к сети.
     * Реализация обязана использовать индекс {@code uk_users_lower_username} и
     * кэш — прямой поход в базу здесь заметен на профиле.
     */
    @NotNull CompletableFuture<Optional<Account>> findByUsername(@NotNull String username);

    @NotNull CompletableFuture<Optional<Account>> findByUuid(@NotNull UUID uuid);

    @NotNull CompletableFuture<Optional<Account>> findById(long id);

    /** Существует ли аккаунт с таким ником. Дешевле, чем {@link #findByUsername(String)}. */
    @NotNull CompletableFuture<Boolean> existsByUsername(@NotNull String username);

    /**
     * Сохраняет новый аккаунт.
     *
     * @return сохранённый аккаунт с проставленным {@code id}
     * @throws net.kofnetwork.auth.api.exception.RepositoryException при нарушении
     *         уникальности ника — гонка двух одновременных регистраций
     */
    @NotNull CompletableFuture<Account> insert(@NotNull Account account);

    /** Обновляет существующий аккаунт целиком. */
    @NotNull CompletableFuture<Account> update(@NotNull Account account);

    /**
     * Точечно обновляет данные последнего входа.
     *
     * <p>Отдельный метод вместо {@link #update(Account)} потому, что это самая частая
     * запись в системе, и переписывать ради неё двадцать колонок — лишняя нагрузка на
     * репликацию и binlog. Заодно снимается риск затереть чужое изменение: полное
     * обновление аккаунта, прочитанного минуту назад, откатило бы всё, что произошло
     * с ним за эту минуту.
     */
    @NotNull CompletableFuture<Void> updateLastLogin(long accountId,
                                                     @NotNull IpAddress ip,
                                                     @NotNull Instant at,
                                                     @Nullable String server,
                                                     @Nullable String country,
                                                     @Nullable String city,
                                                     @Nullable String userAgent);

    /** Фиксирует момент выхода. */
    @NotNull CompletableFuture<Void> updateLastLogout(long accountId, @NotNull Instant at);

    /** Меняет хэш пароля и отметку времени его смены. */
    @NotNull CompletableFuture<Void> updatePassword(long accountId,
                                                    @NotNull String passwordHash,
                                                    @NotNull String algorithm,
                                                    @NotNull Instant at);

    @NotNull CompletableFuture<Void> updateStatus(long accountId, @NotNull AccountStatus status);

    /**
     * Атомарно увеличивает счётчик неудачных попыток.
     *
     * <p>Именно атомарно, на стороне базы ({@code SET failed = failed + 1}), а не чтением
     * с последующей записью: параллельный перебор с нескольких соединений при
     * read-modify-write теряет часть инкрементов, и лимит попыток перестаёт срабатывать.
     *
     * @return новое значение счётчика
     */
    @NotNull CompletableFuture<Integer> incrementFailedAttempts(long accountId);

    /** Сбрасывает счётчик неудач и снимает временную блокировку. */
    @NotNull CompletableFuture<Void> resetFailedAttempts(long accountId);

    /** Ставит временную блокировку до указанного момента. */
    @NotNull CompletableFuture<Void> lockUntil(long accountId, @NotNull Instant until);

    /** Отмечает, что игрок прошёл CAPTCHA. */
    @NotNull CompletableFuture<Void> markCaptchaPassed(long accountId, boolean passed);

    /** Перезаписывает набор включённых вторых факторов. */
    @NotNull CompletableFuture<Void> updateTwoFactorMethods(
            long accountId, @NotNull java.util.Set<net.kofnetwork.auth.api.model.TwoFactorMethod> methods);

    /**
     * Сколько аккаунтов зарегистрировано с адреса за период.
     * Используется AntiBot для ограничения массовой регистрации.
     */
    @NotNull CompletableFuture<Integer> countRegistrationsFromIp(@NotNull IpAddress ip, @NotNull Instant since);

    /** Поиск по префиксу ника для автодополнения админских команд. */
    @NotNull CompletableFuture<List<Account>> searchByUsernamePrefix(@NotNull String prefix, int limit);

    /**
     * Страница аккаунтов в порядке возрастания идентификатора — для выгрузки.
     *
     * <p>Курсор по {@code id}, а не {@code LIMIT ... OFFSET}: смещение заставляет
     * MySQL прочитать и отбросить все пропускаемые строки, и выгрузка стотысячной
     * таблицы вырождается в квадратичную. Кроме того, вставка во время выгрузки
     * сдвигает окно, и часть записей при OFFSET была бы пропущена.
     *
     * @param afterId    брать строки строго больше этого идентификатора; 0 — с начала
     * @param limit      размер страницы
     */
    @NotNull CompletableFuture<List<Account>> findPageAfter(long afterId, int limit);

    /** Полное удаление аккаунта. Связанные записи уходят каскадом. */
    @NotNull CompletableFuture<Boolean> delete(long accountId);

    /** Общее число аккаунтов. Для {@code /auth info} и метрик. */
    @NotNull CompletableFuture<Long> count();
}
