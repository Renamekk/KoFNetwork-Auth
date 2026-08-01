package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.TelegramBinding;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Доступ к таблице {@code telegram}. */
public interface TelegramRepository {

    @NotNull CompletableFuture<TelegramBinding> insert(@NotNull TelegramBinding binding);

    @NotNull CompletableFuture<Optional<TelegramBinding>> findByAccount(long accountId);

    /** Ищет привязку по идентификатору пользователя Telegram — вход в бота начинается отсюда. */
    @NotNull CompletableFuture<Optional<TelegramBinding>> findByTelegramId(long telegramId);

    @NotNull CompletableFuture<TelegramBinding> update(@NotNull TelegramBinding binding);

    @NotNull CompletableFuture<Boolean> deleteByAccount(long accountId);

    /**
     * Привязки, у которых включены уведомления. Используется для рассылки
     * административных сообщений.
     */
    @NotNull CompletableFuture<List<TelegramBinding>> findAllWithNotifications(int limit, int offset);
}
