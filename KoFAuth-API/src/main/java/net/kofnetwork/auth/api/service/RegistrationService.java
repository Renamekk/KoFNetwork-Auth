package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.RegistrationRequest;
import net.kofnetwork.auth.api.result.RegistrationResult;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Регистрация новых аккаунтов. */
public interface RegistrationService {

    /**
     * Создаёт аккаунт и сразу выдаёт сессию.
     *
     * <p>Порядок проверок: включена ли регистрация → rate-limit и лимит на IP →
     * формат ника → свободен ли ник → совпадение паролей → политика сложности →
     * CAPTCHA → создание.
     *
     * <p>Проверка «свободен ли ник» не даёт гарантии: между ней и вставкой возможна
     * гонка с параллельной регистрацией того же ника. Настоящую гарантию даёт
     * уникальный индекс, и реализация обязана корректно обработать его нарушение,
     * вернув {@link net.kofnetwork.auth.api.result.RegistrationResultType#USERNAME_TAKEN},
     * а не техническую ошибку.
     */
    @NotNull CompletableFuture<RegistrationResult> register(@NotNull RegistrationRequest request);

    /**
     * Проверяет пароль на соответствие политике сложности.
     *
     * @return список кодов невыполненных требований; пустой список означает, что пароль принят
     */
    @NotNull List<String> validatePassword(@NotNull String password, @NotNull String username);

    /**
     * Проверяет ник: длина, допустимые символы, чёрный список.
     *
     * @return код проблемы или пустой {@link java.util.Optional}, если ник допустим
     */
    @NotNull java.util.Optional<String> validateUsername(@NotNull String username);

    /** Свободен ли ник. */
    @NotNull CompletableFuture<Boolean> isUsernameAvailable(@NotNull String username);

    /** Открыта ли регистрация. Управляется настройкой {@code auth.registration.enabled}. */
    @NotNull CompletableFuture<Boolean> isRegistrationEnabled();
}
