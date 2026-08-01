package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Роль RBAC. Соответствует строке {@code roles} вместе с раскрытым списком прав.
 *
 * <p>Права загружаются вместе с ролью, а не по запросу: их десятки, они меняются раз
 * в месяц, а проверка права выполняется на каждом действии. Держать их в объекте
 * дешевле, чем ходить в базу.
 *
 * @param priority больший приоритет важнее; определяет отображаемую роль у игрока
 *                 с несколькими ролями
 */
public record Role(
        int id,
        @NotNull String name,
        @NotNull String displayName,
        int priority,
        @Nullable String color,
        boolean defaultRole,
        @NotNull Set<String> permissions,
        @NotNull Instant createdAt
) {

    public Role {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(createdAt, "createdAt");
        permissions = permissions == null || permissions.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
    }

    /**
     * Даёт ли роль указанное право.
     *
     * <p>Поддерживаются подстановочные узлы: право {@code kofauth.admin.*} покрывает
     * {@code kofauth.admin.reload}, а {@code *} — всё. Проверка идёт от точного
     * совпадения к всё более общим маскам, поэтому у роли с {@code kofauth.*} нет нужды
     * перечислять каждый узел.
     */
    public boolean hasPermission(@NotNull String node) {
        if (permissions.contains("*") || permissions.contains(node)) {
            return true;
        }
        int cut = node.length();
        while ((cut = node.lastIndexOf('.', cut - 1)) > 0) {
            if (permissions.contains(node.substring(0, cut) + ".*")) {
                return true;
            }
        }
        return false;
    }

    public @NotNull Role withPermissions(@NotNull Set<String> newPermissions) {
        return new Role(id, name, displayName, priority, color, defaultRole, newPermissions, createdAt);
    }
}
