package net.kofnetwork.auth.core.repository.jdbc;

import net.kofnetwork.auth.api.model.Permission;
import net.kofnetwork.auth.api.model.Role;
import net.kofnetwork.auth.api.repository.RoleRepository;
import net.kofnetwork.auth.core.database.SqlExecutor;
import net.kofnetwork.auth.core.database.SqlTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Реализация {@link RoleRepository} на JDBC. */
public final class JdbcRoleRepository implements RoleRepository {

    private final SqlExecutor sql;

    public JdbcRoleRepository(@NotNull SqlExecutor sql) {
        this.sql = sql;
    }

    /**
     * Все роли вместе с правами одним запросом.
     *
     * <p>Один {@code LEFT JOIN} вместо «выбрать роли, затем для каждой выбрать права»:
     * последнее даёт N+1 запросов, а ролей около десятка, и загружаются они при старте
     * и при каждом {@code /auth reload}.
     */
    @Override
    public @NotNull CompletableFuture<List<Role>> findAllWithPermissions() {
        return sql.queryList("""
                        SELECT r.id, r.name, r.display_name, r.priority, r.color, r.is_default,
                               r.created_at, p.node
                        FROM roles r
                        LEFT JOIN role_permissions rp ON rp.role_id = r.id
                        LEFT JOIN permissions p ON p.id = rp.permission_id
                        ORDER BY r.priority DESC, r.id
                        """,
                JdbcRoleRepository::mapRoleRow,
                new Object[0]
        ).thenApply(JdbcRoleRepository::collapse);
    }

    /** Промежуточное представление строки соединения роли с правом. */
    private record RoleRow(int id, String name, String displayName, int priority,
                           @Nullable String color, boolean defaultRole, Instant createdAt,
                           @Nullable String node) {
    }

    private static RoleRow mapRoleRow(ResultSet rs) throws SQLException {
        return new RoleRow(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("display_name"),
                rs.getInt("priority"),
                rs.getString("color"),
                rs.getBoolean("is_default"),
                SqlTypes.readRequiredInstant(rs, "created_at"),
                rs.getString("node"));
    }

    /** Схлопывает строки соединения в роли с набором прав. */
    private static List<Role> collapse(List<RoleRow> rows) {
        Map<Integer, RoleRow> heads = new LinkedHashMap<>();
        Map<Integer, Set<String>> permissions = new LinkedHashMap<>();

        for (RoleRow row : rows) {
            heads.putIfAbsent(row.id(), row);
            Set<String> nodes = permissions.computeIfAbsent(row.id(), key -> new LinkedHashSet<>());
            if (row.node() != null) {
                nodes.add(row.node());
            }
        }

        List<Role> result = new ArrayList<>(heads.size());
        heads.forEach((id, head) -> result.add(new Role(
                head.id(), head.name(), head.displayName(), head.priority(), head.color(),
                head.defaultRole(), permissions.getOrDefault(id, Set.of()), head.createdAt())));
        return result;
    }

    @Override
    public @NotNull CompletableFuture<Optional<Role>> findByName(@NotNull String name) {
        return findAllWithPermissions().thenApply(roles -> roles.stream()
                .filter(role -> role.name().equalsIgnoreCase(name))
                .findFirst());
    }

    @Override
    public @NotNull CompletableFuture<Optional<Role>> findDefaultRole() {
        return findAllWithPermissions().thenApply(roles -> roles.stream()
                .filter(Role::defaultRole)
                .findFirst());
    }

    @Override
    public @NotNull CompletableFuture<List<Role>> findRolesOfAccount(long accountId,
                                                                     @NotNull Instant at) {
        return sql.queryList("""
                        SELECT r.id, r.name, r.display_name, r.priority, r.color, r.is_default,
                               r.created_at, p.node
                        FROM user_roles ur
                        JOIN roles r ON r.id = ur.role_id
                        LEFT JOIN role_permissions rp ON rp.role_id = r.id
                        LEFT JOIN permissions p ON p.id = rp.permission_id
                        WHERE ur.account_id = ?
                          AND (ur.expires_at IS NULL OR ur.expires_at > ?)
                        ORDER BY r.priority DESC, r.id
                        """,
                JdbcRoleRepository::mapRoleRow,
                accountId, SqlTypes.toTimestamp(at)
        ).thenApply(JdbcRoleRepository::collapse);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> grantRole(long accountId,
                                                         int roleId,
                                                         @Nullable Long grantedBy,
                                                         @Nullable Instant expiresAt) {
        // Повторная выдача обновляет срок, а не падает: продление временной
        // привилегии — обычная операция.
        return sql.update("""
                        INSERT INTO user_roles (account_id, role_id, granted_by, expires_at)
                        VALUES (?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE granted_by = VALUES(granted_by),
                                                expires_at = VALUES(expires_at)
                        """,
                accountId, roleId, grantedBy, SqlTypes.toTimestamp(expiresAt)
        ).thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> revokeRole(long accountId, int roleId) {
        return sql.update("DELETE FROM user_roles WHERE account_id = ? AND role_id = ?",
                accountId, roleId).thenApply(affected -> affected > 0);
    }

    @Override
    public @NotNull CompletableFuture<List<Permission>> findAllPermissions() {
        return sql.queryList("SELECT id, node, description, created_at FROM permissions ORDER BY node",
                rs -> new Permission(
                        rs.getInt("id"),
                        rs.getString("node"),
                        rs.getString("description"),
                        SqlTypes.readRequiredInstant(rs, "created_at")),
                new Object[0]);
    }

    @Override
    public @NotNull CompletableFuture<Integer> purgeExpiredGrants(@NotNull Instant at) {
        return sql.update("DELETE FROM user_roles WHERE expires_at IS NOT NULL AND expires_at <= ?",
                SqlTypes.toTimestamp(at));
    }
}
