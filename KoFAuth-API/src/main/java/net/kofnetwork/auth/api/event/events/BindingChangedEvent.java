package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.AuthEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Изменена привязка внешнего аккаунта: e-mail, Telegram, Discord или TOTP.
 *
 * <p>Одно событие на все виды привязок вместо шести отдельных: подписчики
 * (аудит и уведомления) реагируют на них одинаково, а различие выражается полем
 * {@link #binding()}.
 */
public record BindingChangedEvent(
        long accountIdValue,
        @NotNull BindingKind binding,
        @NotNull Action action,
        @Nullable String target,
        @NotNull AuthContext context,
        @NotNull Instant occurredAt
) implements AuthEvent {

    /** Вид привязки. */
    public enum BindingKind {
        EMAIL, TELEGRAM, DISCORD, TOTP
    }

    /** Что именно произошло. */
    public enum Action {
        LINKED, UNLINKED, VERIFIED, ENABLED, DISABLED
    }

    public BindingChangedEvent {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /**
     * @param target отображаемое значение привязки: маскированный e-mail, {@code @username}.
     *               Полный адрес сюда не кладётся — событие уходит в аудит и в Redis
     */
    public static @NotNull BindingChangedEvent of(long accountId,
                                                  @NotNull BindingKind binding,
                                                  @NotNull Action action,
                                                  @Nullable String target,
                                                  @NotNull AuthContext context) {
        return new BindingChangedEvent(accountId, binding, action, target, context, Instant.now());
    }

    @Override
    public @Nullable Long accountId() {
        return accountIdValue;
    }
}
