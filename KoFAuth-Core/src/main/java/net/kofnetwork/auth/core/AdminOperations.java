package net.kofnetwork.auth.core;

import net.kofnetwork.auth.api.model.AccountStatus;
import net.kofnetwork.auth.api.model.Device;
import net.kofnetwork.auth.api.model.Role;
import net.kofnetwork.auth.api.repository.AccountRepository;
import net.kofnetwork.auth.api.repository.DeviceRepository;
import net.kofnetwork.auth.api.repository.RoleRepository;
import net.kofnetwork.auth.api.repository.SettingsRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Операции, нужные административным командам и панели, но не входящие в
 * пользовательские сервисы.
 *
 * <p>Существует, чтобы не отдавать репозитории наружу целиком. Команда
 * {@code /auth} должна уметь заблокировать аккаунт и посмотреть устройства, но
 * не должна получать возможность выполнить произвольный SQL или обойти аудит —
 * этот класс задаёт ровно тот набор действий, который администратору положен.
 */
public final class AdminOperations {

    private final AccountRepository accounts;
    private final DeviceRepository devices;
    private final RoleRepository roles;
    private final SettingsRepository settings;

    AdminOperations(@NotNull AccountRepository accounts,
                    @NotNull DeviceRepository devices,
                    @NotNull RoleRepository roles,
                    @NotNull SettingsRepository settings) {
        this.accounts = accounts;
        this.devices = devices;
        this.roles = roles;
        this.settings = settings;
    }

    /**
     * Меняет статус аккаунта.
     *
     * <p>При возврате в {@link AccountStatus#ACTIVE} дополнительно сбрасывается
     * счётчик неудачных попыток: иначе разблокированный администратором игрок
     * упёрся бы в ту же временную блокировку через одну попытку.
     */
    public @NotNull CompletableFuture<Void> updateStatus(long accountId,
                                                          @NotNull AccountStatus status) {
        CompletableFuture<Void> update = accounts.updateStatus(accountId, status);
        return status == AccountStatus.ACTIVE
                ? update.thenCompose(ignored -> accounts.resetFailedAttempts(accountId))
                : update;
    }

    /** Устройства аккаунта. */
    public @NotNull CompletableFuture<List<Device>> listDevices(long accountId) {
        return devices.findByAccount(accountId);
    }

    /** Помечает устройство доверенным или снимает доверие. */
    public @NotNull CompletableFuture<Void> setDeviceTrusted(long deviceId, boolean trusted) {
        return devices.setTrusted(deviceId, trusted, Instant.now());
    }

    /** Блокирует устройство. */
    public @NotNull CompletableFuture<Void> setDeviceBlocked(long deviceId, boolean blocked) {
        return devices.setBlocked(deviceId, blocked, Instant.now());
    }

    /**
     * Есть ли у аккаунта право.
     *
     * <p>Права собираются из всех действующих ролей: временная роль с истёкшим
     * сроком в выборку не попадает. Поддерживаются подстановочные узлы —
     * {@code kofauth.admin.*} покрывает {@code kofauth.admin.reload}.
     */
    public @NotNull CompletableFuture<Boolean> hasPermission(long accountId, @NotNull String node) {
        return roles.findRolesOfAccount(accountId, Instant.now())
                .thenApply(list -> list.stream().anyMatch(role -> role.hasPermission(node)));
    }

    /** Роли аккаунта. */
    public @NotNull CompletableFuture<List<Role>> listRoles(long accountId) {
        return roles.findRolesOfAccount(accountId, Instant.now());
    }

    /** Выдаёт роль. */
    public @NotNull CompletableFuture<Boolean> grantRole(long accountId,
                                                          int roleId,
                                                          @Nullable Long grantedBy,
                                                          @Nullable Instant expiresAt) {
        return roles.grantRole(accountId, roleId, grantedBy, expiresAt);
    }

    /** Отзывает роль. */
    public @NotNull CompletableFuture<Boolean> revokeRole(long accountId, int roleId) {
        return roles.revokeRole(accountId, roleId);
    }

    /** Все динамические настройки. */
    public @NotNull CompletableFuture<Map<String, String>> allSettings() {
        return settings.getAll();
    }

    /** Меняет динамическую настройку. */
    public @NotNull CompletableFuture<Void> setSetting(@NotNull String key,
                                                        @Nullable String value,
                                                        @Nullable Long updatedBy) {
        return settings.set(key, value, updatedBy);
    }

    /** Число аккаунтов в сети. */
    public @NotNull CompletableFuture<Long> countAccounts() {
        return accounts.count();
    }

    /**
     * Аккаунт по идентификатору.
     *
     * <p>Нужен ботам: они опознают игрока по привязке Telegram или Discord и
     * получают на руки только {@code accountId}, а сервисный слой ищет по нику.
     */
    public @NotNull CompletableFuture<java.util.Optional<net.kofnetwork.auth.api.model.Account>>
            findAccount(long accountId) {
        return accounts.findById(accountId);
    }

    /** Поиск аккаунтов по префиксу ника — для автодополнения. */
    public @NotNull CompletableFuture<List<String>> suggestUsernames(@NotNull String prefix,
                                                                     int limit) {
        return accounts.searchByUsernamePrefix(prefix, limit)
                .thenApply(list -> list.stream()
                        .map(net.kofnetwork.auth.api.model.Account::username)
                        .toList());
    }
}
