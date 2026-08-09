package net.kofnetwork.auth.api.event.events;

import net.kofnetwork.auth.api.event.AuthEvent;
import net.kofnetwork.auth.api.model.ApprovalStatus;
import net.kofnetwork.auth.api.model.BotPlatform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Владелец принял решение по запросу подтверждения входа.
 *
 * <p>Событие распределённое, и это не оптимизация, а необходимость: кнопку нажимают
 * в процессе бота, обращающемся к WebAPI, а ждёт решения игрок, подключённый к
 * Velocity — другому процессу. Раньше связи между ними не было вовсе: погашение кода
 * создавало отдельную сессию мессенджера, никто никого не уведомлял, и игрок оставался
 * в состоянии {@code TWO_FACTOR_REQUIRED} до самого таймаута, даже нажав «Это я».
 *
 * <p>Прокси, получив событие, сверяет {@link #attemptId()} с попыткой, которую ведёт
 * сам. Несовпадение означает, что игрок успел начать вход заново, и решение по прежней
 * попытке к делу не относится.
 *
 * @param sessionPublicId сессия, созданная одобрением; {@code null} при отказе
 */
public record LoginApprovalDecidedEvent(
        @NotNull Long accountId,
        @NotNull String username,
        @Nullable UUID playerUuid,
        @NotNull String attemptId,
        @NotNull String approvalPublicId,
        @NotNull BotPlatform platform,
        @NotNull ApprovalStatus status,
        @Nullable String sessionPublicId,
        @NotNull Instant occurredAt
) implements AuthEvent {

    public LoginApprovalDecidedEvent {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(approvalPublicId, "approvalPublicId");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static @NotNull LoginApprovalDecidedEvent of(long accountId,
                                                        @NotNull String username,
                                                        @Nullable UUID playerUuid,
                                                        @NotNull String attemptId,
                                                        @NotNull String approvalPublicId,
                                                        @NotNull BotPlatform platform,
                                                        @NotNull ApprovalStatus status,
                                                        @Nullable String sessionPublicId) {
        return new LoginApprovalDecidedEvent(accountId, username, playerUuid, attemptId,
                approvalPublicId, platform, status, sessionPublicId, Instant.now());
    }

    public boolean isApproved() {
        return status == ApprovalStatus.APPROVED;
    }
}
