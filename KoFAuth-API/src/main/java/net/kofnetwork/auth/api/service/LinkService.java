package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.DiscordBinding;
import net.kofnetwork.auth.api.model.TelegramBinding;
import net.kofnetwork.auth.api.result.OperationResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
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

    // ------------------------------------------------------------------ привязка сайта

    /**
     * Начинает привязку игрового аккаунта к сессии браузера.
     *
     * <p><b>Направление то же, что и у мессенджеров, и по той же причине.</b> Сайт
     * не может доказать владение игровым аккаунтом — доказать его можно только
     * в игре. Поэтому сайт лишь показывает код, а подтверждает его игрок командой
     * {@code /link <код>}, находясь в аккаунте. Обратный порядок («введите ник на
     * сайте, подтвердите в игре») позволил бы начать привязку к любому чужому нику
     * и завалить владельца запросами.
     *
     * <p>Схема повторяет device authorization grant: у сайта есть код для человека
     * и отдельный секрет для опроса. Это разные значения намеренно — код короткий,
     * его видно на экране и диктуют вслух, а {@code pollToken} предъявляет право
     * получить токены доступа и не должен быть угадываемым.
     *
     * @return код для игрока и секрет для опроса состояния
     */
    @NotNull CompletableFuture<OperationResult<WebLinkRequest>> startWebLink(@NotNull AuthContext context);

    /**
     * Запрос сайта на привязку.
     *
     * @param code      короткий код, который игрок вводит в игре
     * @param pollToken секрет браузера для опроса состояния
     * @param ttl       срок жизни запроса
     */
    record WebLinkRequest(@NotNull String code, @NotNull String pollToken, @NotNull Duration ttl) {
    }

    /**
     * Подтверждает запрос сайта из игры.
     *
     * <p>Вызывается командой {@code /link} только для вошедшего игрока: привязка
     * означает выдачу браузеру полного доступа к аккаунту, и подтверждать её
     * вправе лишь тот, кто уже доказал владение паролем.
     *
     * @param playerUuid UUID подтвердившего; именно он, а не ник, попадает в
     *                   привязку — ник можно сменить, UUID останется прежним
     */
    @NotNull CompletableFuture<OperationResult<Void>> confirmWebLink(@NotNull String code,
                                                                      @NotNull UUID playerUuid,
                                                                      long accountId,
                                                                      @NotNull AuthContext context);

    /**
     * Опрашивает состояние запроса.
     *
     * <p>Подтверждение выдаётся <em>один раз</em>: второй опрос по тому же
     * {@code pollToken} вернёт пустое значение. Иначе секрет, попавший в историю
     * браузера или в лог обратного прокси, оставался бы годным ключом к аккаунту
     * до истечения срока.
     *
     * @return пустое значение, пока игрок не подтвердил либо если запрос истёк
     */
    @NotNull CompletableFuture<Optional<WebLinkConfirmation>> pollWebLink(@NotNull String pollToken);

    /**
     * Подтверждённая привязка.
     *
     * @param playerUuid UUID игрока — ключ привязки
     */
    record WebLinkConfirmation(long accountId,
                               @NotNull UUID playerUuid,
                               @NotNull String username) {
    }

    /**
     * Завершает привязку Discord, личность в котором уже подтверждена OAuth2.
     *
     * <p><b>Обмен кода на токен сюда не входит.</b> Он требует HTTP-клиента и знания
     * эндпоинтов Discord, а Core не должен зависеть ни от того, ни от другого: его
     * вызывают и прокси, и боты, которым исходящий HTTP не нужен вовсе. Обмен и
     * проверку параметра {@code state} выполняет веб-модуль, а сюда приходит уже
     * установленный идентификатор.
     *
     * <p>Отсюда и разница с {@link #completeDiscordLink}: там доверие даёт одноразовый
     * код, выданный в игре, здесь — подпись Discord под ответом на обмен кода.
     *
     * @param discordId идентификатор, полученный от {@code /users/@me}
     * @param username  ник для отображения; {@code null}, если не запрошен
     */
    @NotNull CompletableFuture<OperationResult<DiscordBinding>> linkVerifiedDiscord(long accountId,
                                                                                    long discordId,
                                                                                    @Nullable String username,
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

    /**
     * Включает или выключает уведомления безопасности в Telegram.
     *
     * <p>Отдельная настройка от подтверждения входа, и объединять их нельзя.
     * Подтверждение решает, пускать ли; уведомление лишь сообщает о том, что уже
     * случилось. Владелец вправе оставить второй фактор и при этом не получать
     * сообщение о каждом своём входе — и наоборот, следить за входами, не включая
     * подтверждение.
     */
    @NotNull CompletableFuture<OperationResult<Void>> setTelegramNotifications(long accountId,
                                                                               boolean enabled);

    /** Включает или выключает уведомления безопасности в Discord. */
    @NotNull CompletableFuture<OperationResult<Void>> setDiscordNotifications(long accountId,
                                                                              boolean enabled);
}
