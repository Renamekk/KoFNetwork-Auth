package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.DiscordBinding;
import net.kofnetwork.auth.api.model.TelegramBinding;
import net.kofnetwork.auth.api.result.OperationResult;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Привязка внешних аккаунтов: Telegram и Discord.
 *
 * <p><b>Направление привязки.</b> Код всегда выдаётся в игре и вводится в мессенджере,
 * а не наоборот. Причина в том, что доказать владение игровым аккаунтом можно только
 * внутри игры: если бы код выдавал бот, любой знающий чужой ник получил бы код и
 * привязал к себе чужой аккаунт.
 */
public interface LinkService {

    /**
     * Выдаёт одноразовый код привязки Telegram.
     *
     * @return код, который игрок отправляет боту командой {@code /link <код>}
     */
    @NotNull CompletableFuture<OperationResult<LinkCode>> createTelegramLinkCode(long accountId,
                                                                                 @NotNull AuthContext context);

    /** Выдаёт одноразовый код привязки Discord. */
    @NotNull CompletableFuture<OperationResult<LinkCode>> createDiscordLinkCode(long accountId,
                                                                                @NotNull AuthContext context);

    /**
     * Код привязки.
     *
     * @param code    значение для ввода игроком
     * @param ttl     срок жизни
     */
    record LinkCode(@NotNull String code, @NotNull Duration ttl) {
    }

    /**
     * Завершает привязку Telegram по коду.
     *
     * <p>Вызывается ботом. Код гасится атомарно, поэтому одновременная попытка
     * использовать его дважды не создаст две привязки.
     */
    @NotNull CompletableFuture<OperationResult<TelegramBinding>> completeTelegramLink(@NotNull String code,
                                                                                      long telegramId,
                                                                                      long chatId,
                                                                                      @NotNull AuthContext context);

    /** Завершает привязку Discord по коду. */
    @NotNull CompletableFuture<OperationResult<DiscordBinding>> completeDiscordLink(@NotNull String code,
                                                                                    long discordId,
                                                                                    @NotNull AuthContext context);

    /**
     * Завершает привязку Discord через OAuth2 с сайта.
     *
     * @param state параметр CSRF-защиты, выданный при начале авторизации; проверяется
     *              на совпадение, иначе злоумышленник может привязать свой Discord
     *              к чужому аккаунту, подсунув владельцу ссылку возврата
     */
    @NotNull CompletableFuture<OperationResult<DiscordBinding>> completeDiscordOauth(long accountId,
                                                                                     @NotNull String authorizationCode,
                                                                                     @NotNull String state,
                                                                                     @NotNull AuthContext context);

    @NotNull CompletableFuture<OperationResult<Void>> unlinkTelegram(long accountId, @NotNull AuthContext context);

    @NotNull CompletableFuture<OperationResult<Void>> unlinkDiscord(long accountId, @NotNull AuthContext context);

    @NotNull CompletableFuture<Optional<TelegramBinding>> findTelegram(long accountId);

    @NotNull CompletableFuture<Optional<DiscordBinding>> findDiscord(long accountId);

    /** Ищет аккаунт по идентификатору Telegram — точка входа команд бота. */
    @NotNull CompletableFuture<Optional<Long>> findAccountByTelegramId(long telegramId);

    /** Ищет аккаунт по идентификатору Discord. */
    @NotNull CompletableFuture<Optional<Long>> findAccountByDiscordId(long discordId);

    /** Включает или выключает подтверждение входа через Telegram. */
    @NotNull CompletableFuture<OperationResult<Void>> setTelegramLoginApproval(long accountId, boolean enabled);

    /** Включает или выключает подтверждение входа через Discord. */
    @NotNull CompletableFuture<OperationResult<Void>> setDiscordLoginApproval(long accountId, boolean enabled);
}
