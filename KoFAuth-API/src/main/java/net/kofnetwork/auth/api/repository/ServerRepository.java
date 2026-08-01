package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.ServerNode;
import net.kofnetwork.auth.api.model.ServerType;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Доступ к таблице {@code servers}. */
public interface ServerRepository {

    /** Регистрирует сервер или обновляет запись, если имя уже известно. */
    @NotNull CompletableFuture<ServerNode> register(@NotNull ServerNode server);

    @NotNull CompletableFuture<Optional<ServerNode>> findByName(@NotNull String name);

    @NotNull CompletableFuture<List<ServerNode>> findByType(@NotNull ServerType type);

    @NotNull CompletableFuture<List<ServerNode>> findAll();

    /**
     * Доступные серверы типа: онлайн, со свежим heartbeat и свободными слотами,
     * отсортированные по приоритету и загрузке.
     *
     * <p>Именно этот запрос выбирает Limbo для игрока, поэтому фильтрация по свежести
     * heartbeat выполняется в SQL, а не в Java: иначе прокси при каждом подключении
     * вычитывал бы весь реестр, чтобы отбросить мёртвые узлы.
     */
    @NotNull CompletableFuture<List<ServerNode>> findAvailable(@NotNull ServerType type, @NotNull Instant at);

    /** Обновляет heartbeat и загрузку. */
    @NotNull CompletableFuture<Void> heartbeat(@NotNull String name,
                                               @NotNull Instant at,
                                               int playerCount,
                                               int maxPlayers);

    @NotNull CompletableFuture<Void> markOffline(@NotNull String name);

    @NotNull CompletableFuture<Boolean> delete(@NotNull String name);
}
