package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.LoginRequest;
import net.kofnetwork.auth.api.dto.PasswordChangeRequest;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.result.AuthResult;
import net.kofnetwork.auth.api.result.OperationResult;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Аутентификация: проверка учётных данных, второй фактор, смена и сброс пароля.
 *
 * <p>Сервис отвечает на вопрос «этот ли человек владеет аккаунтом». Что делать дальше —
 * куда телепортировать, что показать, на какой сервер отправить — решают платформенные
 * модули по возвращённому {@link AuthResult}.
 *
 * <p><b>Постоянное время ответа.</b> Реализация обязана тратить сопоставимое время на
 * несуществующий аккаунт и на неверный пароль. Иначе разница в задержке позволяет
 * перебрать существующие ники, даже когда текст ошибки одинаков: проверка BCrypt
 * занимает ~100 мс, а ответ «нет такого аккаунта» без неё вернулся бы за миллисекунду.
 */
public interface AuthenticationService {

    /**
     * Проверяет учётные данные и при успехе создаёт сессию.
     *
     * <p>Полный порядок проверок: rate-limit → AntiBot → существование аккаунта →
     * статус и временная блокировка → пароль → CAPTCHA → второй фактор → создание сессии.
     * Порядок существенен: дорогая проверка BCrypt выполняется после дешёвых отсечек,
     * иначе перебор паролей нагружает CPU сервера сильнее, чем машину атакующего.
     *
     * <p>Future не завершается исключением при неудачном входе — неуспех выражается
     * значением {@link AuthResult#type()}. Исключение означает отказ инфраструктуры.
     */
    @NotNull CompletableFuture<AuthResult> login(@NotNull LoginRequest request);

    /**
     * Завершает вход вводом кода второго фактора.
     *
     * <p>Вызывается после того, как {@link #login(LoginRequest)} вернул
     * {@link net.kofnetwork.auth.api.model.LoginResultType#TWO_FACTOR_REQUIRED}.
     *
     * @param code код TOTP или резервный код
     */
    @NotNull CompletableFuture<AuthResult> completeTwoFactor(@NotNull UUID playerUuid,
                                                             @NotNull String code,
                                                             @NotNull AuthContext context);

    /**
     * Завершает вход, подтверждённый кнопкой в мессенджере.
     *
     * <p>Вызывается не ботом и не контроллером, а
     * {@link LoginApprovalService}, когда решение владельца уже записано атомарно.
     * Прежний метод принимал предъявительский токен и потому мог быть вызван кем
     * угодно и когда угодно — в том числе без живой попытки входа; здесь на входе
     * запись подтверждения, которая существует только как продолжение проверки пароля.
     *
     * @return сессия исходной попытки (GAME либо WEB, согласно сохранённому контексту)
     *         либо пустое значение, если завершить вход не удалось
     */
    @NotNull CompletableFuture<java.util.Optional<net.kofnetwork.auth.api.model.Session>>
            completeApprovedLogin(@NotNull net.kofnetwork.auth.api.model.LoginApproval approval);

    /**
     * Проверяет пароль без создания сессии.
     *
     * <p>Нужно для операций, требующих повторного подтверждения личности: смена почты,
     * отключение TOTP, удаление аккаунта. Счётчик неудачных попыток при этом ведётся
     * так же, как при входе.
     */
    @NotNull CompletableFuture<Boolean> verifyPassword(long accountId, @NotNull String password);

    /** Меняет пароль с подтверждением текущего. */
    @NotNull CompletableFuture<OperationResult<Void>> changePassword(long accountId,
                                                                     @NotNull PasswordChangeRequest request);

    /**
     * Запрашивает сброс пароля: отправляет код на подтверждённый e-mail.
     *
     * <p>Возвращает успех и в том случае, когда аккаунта или привязанной почты нет.
     * Различимый ответ превратил бы форму восстановления в способ проверять,
     * зарегистрирован ли ник и есть ли у него почта.
     */
    @NotNull CompletableFuture<OperationResult<Void>> requestPasswordReset(@NotNull String username,
                                                                           @NotNull AuthContext context);

    /**
     * Завершает сброс пароля по одноразовому коду.
     *
     * <p>Все сессии аккаунта отзываются: сброс пароля обычно означает, что доступ
     * пытались перехватить.
     */
    @NotNull CompletableFuture<OperationResult<Void>> completePasswordReset(@NotNull String resetToken,
                                                                            @NotNull String newPassword,
                                                                            @NotNull AuthContext context);

    /**
     * Устанавливает пароль администратором, без знания текущего.
     *
     * @param adminAccountId кто выполняет — попадёт в аудит как {@code actor_id}
     */
    @NotNull CompletableFuture<OperationResult<Void>> adminResetPassword(long accountId,
                                                                          @NotNull String newPassword,
                                                                          long adminAccountId,
                                                                          @NotNull AuthContext context);

    /** Загружает аккаунт по нику без учёта регистра. */
    @NotNull CompletableFuture<java.util.Optional<Account>> findAccount(@NotNull String username);
}
