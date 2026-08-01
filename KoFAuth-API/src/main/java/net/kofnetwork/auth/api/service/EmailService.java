package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.EmailBinding;
import net.kofnetwork.auth.api.result.OperationResult;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Работа с почтой: привязка, подтверждение, отправка писем.
 *
 * <p>Отправка идёт на отдельном пуле {@code kofauth-mail}. SMTP-сервер может отвечать
 * секундами, и общий пул с базой означал бы, что зависший почтовый сервер останавливает
 * входы на всей сети.
 */
public interface EmailService {

    /**
     * Привязывает адрес и отправляет код подтверждения.
     *
     * <p>Адрес считается непривязанным до подтверждения: иначе привязка чужой почты
     * стала бы способом перехватить восстановление пароля.
     */
    @NotNull CompletableFuture<OperationResult<Void>> linkEmail(long accountId,
                                                                @NotNull String email,
                                                                @NotNull AuthContext context);

    /** Подтверждает адрес кодом из письма. */
    @NotNull CompletableFuture<OperationResult<Void>> verifyEmail(@NotNull String token,
                                                                  @NotNull AuthContext context);

    /**
     * Отправляет код подтверждения повторно.
     *
     * <p>Подпадает под ограничение скорости: без него форма повторной отправки
     * превращается в бесплатный генератор писем на любой адрес.
     */
    @NotNull CompletableFuture<OperationResult<Void>> resendVerification(long accountId,
                                                                         @NotNull AuthContext context);

    /** Отвязывает адрес. */
    @NotNull CompletableFuture<OperationResult<Void>> unlinkEmail(long accountId,
                                                                  @NotNull AuthContext context);

    /** Основной адрес аккаунта. */
    @NotNull CompletableFuture<Optional<EmailBinding>> findPrimary(long accountId);

    @NotNull CompletableFuture<List<EmailBinding>> findAll(long accountId);

    /** Меняет настройки уведомлений. */
    @NotNull CompletableFuture<OperationResult<Void>> updateNotificationSettings(long accountId,
                                                                                 boolean notifyLogin,
                                                                                 boolean notifySecurity,
                                                                                 boolean notifyNewsletter);

    /**
     * Отправляет письмо по шаблону.
     *
     * <p>Прямой отправки произвольного текста нет намеренно: письма от системы
     * авторизации должны выглядеть одинаково и содержать одни и те же предупреждения
     * о фишинге, а свободный текст в вызове это гарантировать не может.
     *
     * @param template  идентификатор шаблона: {@code verify-email}, {@code password-reset},
     *                  {@code new-login}, {@code security-alert}
     * @param variables подстановки шаблона
     */
    @NotNull CompletableFuture<OperationResult<Void>> sendTemplated(@NotNull String to,
                                                                    @NotNull String template,
                                                                    @NotNull Map<String, String> variables);

    /**
     * Проверяет допустимость адреса: формат, а также чёрный список одноразовых доменов.
     *
     * @return код проблемы либо пустой {@link Optional}
     */
    @NotNull Optional<String> validateEmail(@NotNull String email);

    /** Настроена ли отправка почты. При {@code false} все операции с почтой отключены. */
    boolean isConfigured();
}
