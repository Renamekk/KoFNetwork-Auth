package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Право доступа. Соответствует строке {@code permissions}.
 *
 * @param node узел вида {@code kofauth.admin.unlock}
 */
public record Permission(
        int id,
        @NotNull String node,
        @Nullable String description,
        @NotNull Instant createdAt
) {

    public Permission {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Родительский узел: для {@code a.b.c} это {@code a.b}. {@code null} для корневого. */
    public @Nullable String parentNode() {
        int lastDot = node.lastIndexOf('.');
        return lastDot > 0 ? node.substring(0, lastDot) : null;
    }
}
