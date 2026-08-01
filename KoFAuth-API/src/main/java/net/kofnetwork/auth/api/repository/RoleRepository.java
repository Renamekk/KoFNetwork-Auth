package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.Permission;
import net.kofnetwork.auth.api.model.Role;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Доступ к таблицам {@code roles}, {@code permissions}, {@code role_permissions}, {@code user_roles}. */
public interface RoleRepository {

    /**
     * Все роли с раскрытыми правами.
     *
     * <p>Роли меняются редко, а читаются на каждой проверке права, поэтому загружаются
     * целиком при старте и кэшируются до {@code /auth reload}.
     */
    @NotNull CompletableFuture<List<Role>> findAllWithPermissions();

    @NotNull CompletableFuture<Optional<Role>> findByName(@NotNull String name);

    /** Роль, выдаваемая при регистрации. */
    @NotNull CompletableFuture<Optional<Role>> findDefaultRole();

    /** Роли аккаунта с учётом срока действия временных выдач. */
    @NotNull CompletableFuture<List<Role>> findRolesOfAccount(long accountId, @NotNull Instant at);

    /**
     * Выдаёт роль аккаунту.
     *
     * @param expiresAt {@code null} — бессрочно
     * @param grantedBy кто выдал; {@code null} для автоматической выдачи при регистрации
     */
    @NotNull CompletableFuture<Boolean> grantRole(long accountId,
                                                  int roleId,
                                                  @Nullable Long grantedBy,
                                                  @Nullable Instant expiresAt);

    @NotNull CompletableFuture<Boolean> revokeRole(long accountId, int roleId);

    @NotNull CompletableFuture<List<Permission>> findAllPermissions();

    /** Удаляет истёкшие временные выдачи ролей. Вызывается планировщиком. */
    @NotNull CompletableFuture<Integer> purgeExpiredGrants(@NotNull Instant at);
}
