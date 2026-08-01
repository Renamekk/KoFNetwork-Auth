package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.event.AuthEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Событие, пришедшее с другого узла сети.
 *
 * <p><b>Зачем отдельный тип, а не доставка исходного события.</b> Богатые доменные
 * события несут внутри {@code Account}, а у него есть {@code passwordHash}. Отправлять
 * их «как есть» в Redis означало бы класть хэши паролей всей сети в канал Pub/Sub,
 * где они осядут в дампах и в мониторинге. Поэтому через межпроцессную шину едут
 * только скалярные атрибуты, а получатель, которому нужен полный объект, загружает
 * его из репозитория — на своём узле, с обычными правами доступа.
 *
 * <p><b>Кто на это подписывается.</b> Компоненты, работающие в отдельных процессах:
 * Telegram- и Discord-боты (уведомления о входе), Velocity (разрыв сессии при смене
 * пароля на сайте). Локальные подписчики на том же узле продолжают получать
 * исходное доменное событие со всеми полями.
 *
 * @param type       имя исходного класса события, например {@code AccountLoginEvent}
 * @param nodeId     идентификатор узла-отправителя; свои же события отбрасываются
 * @param attributes скалярные атрибуты; секретов здесь быть не может по построению
 */
public record RemoteEvent(
        @NotNull String type,
        @NotNull String nodeId,
        @Nullable Long accountId,
        @NotNull Map<String, String> attributes,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public RemoteEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        attributes = attributes == null || attributes.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * Уже доставлено по сети — повторная отправка создала бы бесконечный цикл
     * пересылки между узлами.
     */
    @Override
    public boolean isDistributed() {
        return false;
    }

    /** Соответствует ли событие исходному типу. */
    public boolean isType(@NotNull Class<? extends AuthEvent> eventType) {
        return type.equals(eventType.getSimpleName());
    }

    public @Nullable String attribute(@NotNull String key) {
        return attributes.get(key);
    }

    public @NotNull String attribute(@NotNull String key, @NotNull String defaultValue) {
        return attributes.getOrDefault(key, defaultValue);
    }

    public boolean booleanAttribute(@NotNull String key) {
        return Boolean.parseBoolean(attributes.get(key));
    }

    /** Числовой атрибут или {@code null}, если он отсутствует либо не разбирается. */
    public @Nullable Long longAttribute(@NotNull String key) {
        String raw = attributes.get(key);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
