package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Привязанный почтовый адрес. Соответствует строке {@code emails}.
 *
 * <p>Непроверенный адрес ({@code verified == false}) не даёт ничего: с него нельзя
 * восстановить пароль и на него не уходят уведомления. Иначе привязка чужой почты
 * стала бы способом угона аккаунта.
 */
public record EmailBinding(
        long id,
        long accountId,
        @NotNull String email,
        @NotNull String emailLower,
        boolean verified,
        @Nullable Instant verifiedAt,
        boolean primary,
        boolean notifyLogin,
        boolean notifySecurity,
        boolean notifyNewsletter,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt
) {

    public EmailBinding {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(emailLower, "emailLower");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Создаёт непроверенную привязку. Подтверждение приходит отдельным шагом. */
    public static @NotNull EmailBinding pending(long accountId, @NotNull String email, boolean primary) {
        Instant now = Instant.now();
        return new EmailBinding(0L, accountId, email, normalize(email), false, null,
                primary, true, true, false, now, now);
    }

    /**
     * Нормализует адрес для поиска и сравнения: обрезает пробелы и приводит к нижнему регистру.
     *
     * <p>Плюс-адресация ({@code user+tag@gmail.com}) намеренно не схлопывается: это
     * поведение специфично для конкретных провайдеров, и «умная» нормализация приведёт
     * к тому, что у части пользователей адрес перестанет совпадать с тем, что они ввели.
     */
    public static @NotNull String normalize(@NotNull String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Отмечает адрес подтверждённым. */
    public @NotNull EmailBinding verify(@NotNull Instant at) {
        return new EmailBinding(id, accountId, email, emailLower, true, at, primary,
                notifyLogin, notifySecurity, notifyNewsletter, createdAt, at);
    }

    public @NotNull EmailBinding withNotifications(boolean login, boolean security, boolean newsletter) {
        return new EmailBinding(id, accountId, email, emailLower, verified, verifiedAt, primary,
                login, security, newsletter, createdAt, Instant.now());
    }

    public @NotNull EmailBinding withId(long newId) {
        return new EmailBinding(newId, accountId, email, emailLower, verified, verifiedAt, primary,
                notifyLogin, notifySecurity, notifyNewsletter, createdAt, updatedAt);
    }

    /** Можно ли использовать адрес для восстановления пароля и уведомлений. */
    public boolean isUsable() {
        return verified;
    }
}
