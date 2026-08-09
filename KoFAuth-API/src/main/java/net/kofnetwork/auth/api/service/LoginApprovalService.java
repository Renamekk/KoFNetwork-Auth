package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.ApprovalStatus;
import net.kofnetwork.auth.api.model.BotPlatform;
import net.kofnetwork.auth.api.model.LoginApproval;
import net.kofnetwork.auth.api.result.OperationResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Подтверждение входа нажатием кнопки в мессенджере.
 *
 * <p>Подтверждение выпускает сервер и только после того, как пароль уже проверен.
 * Ни бот, ни игрок выпустить его не могут — иначе подтверждение перестало бы быть
 * <em>вторым</em> фактором и превратилось в самостоятельный способ войти.
 *
 * <p>Игроку в Minecraft ничего вводить не нужно: кодов нет, дополнительных команд нет.
 * Единственное действие — нажать «Войти» или «Отклонить» в своём мессенджере.
 */
public interface LoginApprovalService {

    /**
     * Выпускает подтверждение для попытки входа и ставит его в очередь боту.
     *
     * <p>Прежние ожидающие подтверждения того же аккаунта гасятся: держать несколько
     * действующих кнопок значит расширять окно, в котором вход можно подтвердить.
     *
     * @param attemptId идентификатор попытки входа; решение по устаревшей попытке
     *                  ничего не откроет
     * @return {@code REQUEST_UNAVAILABLE}, если у аккаунта нет привязки к платформе
     *         либо подтверждение входа для неё выключено
     */
    @NotNull CompletableFuture<OperationResult<LoginApproval>> request(@NotNull Account account,
                                                                        @Nullable UUID playerUuid,
                                                                        @NotNull String attemptId,
                                                                        @NotNull BotPlatform platform,
                                                                        @NotNull AuthContext context,
                                                                        @Nullable String browserProofHash);

    /**
     * Применяет решение владельца.
     *
     * <p>Проверяется, что нажавший — тот, кому кнопка адресована; что запрос ещё жив;
     * и что решение принимается впервые. Всё это выполняется атомарно, поэтому два
     * одновременных нажатия расходятся на «принято» и «уже решено», а не создают
     * две сессии.
     *
     * <p>Одобрение <b>завершает исходную попытку входа</b>: для игры создаёт игровую
     * сессию и публикует {@code LoginApprovalDecidedEvent}; для WEB создаёт именно
     * WEB-сессию, которую исходная вкладка заберёт защищённым одноразовым exchange.
     * Отдельной сессии мессенджера не появляется.
     *
     * @param pressedBy идентификатор нажавшего в мессенджере
     */
    @NotNull CompletableFuture<Decision> decide(@NotNull String approvalPublicId,
                                                 @NotNull BotPlatform pressedOn,
                                                 long pressedBy,
                                                 boolean approved);

    /** Текущее состояние подтверждения — для повторного показа кнопки после рестарта бота. */
    @NotNull CompletableFuture<Optional<LoginApproval>> find(@NotNull String approvalPublicId);

    /** Состояние веб-ожидания. Неверный proof намеренно не раскрывает, существует ли
     * попытка, и потому выглядит как отсутствующая. */
    @NotNull CompletableFuture<WebAttempt> webStatus(@NotNull String attemptId,
                                                      @NotNull String browserProof);

    /** Одноразово связывает одобренную WEB-сессию с исходной вкладкой. */
    @NotNull CompletableFuture<WebExchange> exchangeWeb(@NotNull String attemptId,
                                                        @NotNull String browserProof);

    record WebAttempt(@NotNull ApprovalStatus status, boolean ready) {
    }

    record WebExchange(boolean consumed, @Nullable Long accountId, @Nullable Long sessionId,
                       @NotNull ApprovalStatus status) {
    }

    /** Исход попытки принять решение. */
    enum DecisionResult {

        /** Решение записано этим нажатием. */
        APPLIED,

        /** Запроса с таким идентификатором нет. */
        NOT_FOUND,

        /** Нажал не тот, кому кнопка адресована. */
        FOREIGN,

        /** Срок истёк. */
        EXPIRED,

        /** Решение уже было принято раньше. */
        ALREADY_DECIDED
    }

    /**
     * @param status   состояние подтверждения после попытки
     * @param username ник, чтобы бот мог показать осмысленный текст, не запрашивая профиль
     */
    record Decision(@NotNull DecisionResult result,
                    @NotNull ApprovalStatus status,
                    @Nullable String username) {

        public static @NotNull Decision of(@NotNull DecisionResult result) {
            return new Decision(result, ApprovalStatus.PENDING, null);
        }

        public boolean isApplied() {
            return result == DecisionResult.APPLIED;
        }
    }
}
