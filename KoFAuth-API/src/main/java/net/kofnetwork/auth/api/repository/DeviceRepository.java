package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.Device;
import net.kofnetwork.auth.api.model.IpAddress;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Доступ к таблице {@code devices}. */
public interface DeviceRepository {

    /**
     * Находит устройство по отпечатку либо создаёт запись, если оно встречено впервые.
     *
     * <p>Одна операция вместо пары «найти, если нет — вставить»: два входа с одного
     * устройства в один момент (например, при переподключении) иначе гонятся за
     * уникальным ключом {@code uk_devices_account_fingerprint}, и один из них падает.
     * Реализация использует {@code INSERT ... ON DUPLICATE KEY UPDATE}.
     *
     * @return запись устройства и признак того, что оно создано только что —
     *         по нему сервис решает, слать ли уведомление «вход с нового устройства»
     */
    @NotNull CompletableFuture<UpsertResult> findOrCreate(long accountId,
                                                          @NotNull String fingerprint,
                                                          @NotNull net.kofnetwork.auth.api.model.DevicePlatform platform,
                                                          @NotNull IpAddress ip,
                                                          @NotNull Instant at);

    /** Результат {@link #findOrCreate}. */
    record UpsertResult(@NotNull Device device, boolean created) {
    }

    @NotNull CompletableFuture<Optional<Device>> findById(long id);

    @NotNull CompletableFuture<Optional<Device>> findByFingerprint(long accountId, @NotNull String fingerprint);

    /** Устройства аккаунта, от недавно использованных к старым. */
    @NotNull CompletableFuture<List<Device>> findByAccount(long accountId);

    @NotNull CompletableFuture<Device> update(@NotNull Device device);

    /** Помечает устройство доверенным или снимает доверие. */
    @NotNull CompletableFuture<Void> setTrusted(long deviceId, boolean trusted, @NotNull Instant at);

    @NotNull CompletableFuture<Void> setBlocked(long deviceId, boolean blocked, @NotNull Instant at);

    @NotNull CompletableFuture<Boolean> delete(long deviceId);

    /** Число устройств аккаунта. */
    @NotNull CompletableFuture<Integer> countByAccount(long accountId);

    /** Удаляет устройства, не появлявшиеся дольше указанного срока. */
    @NotNull CompletableFuture<Integer> deleteStale(@NotNull Instant lastSeenBefore);
}
