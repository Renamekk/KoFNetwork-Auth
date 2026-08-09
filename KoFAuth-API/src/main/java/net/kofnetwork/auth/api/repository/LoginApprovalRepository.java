package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.ApprovalStatus;
import net.kofnetwork.auth.api.model.LoginApproval;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ к таблице {@code login_approvals}.
 *
 * <p>Хранилище — MySQL, а не Redis, и это принципиально. Подтверждение обязано пережить
 * перезапуск бота и перезапуск процесса, который его выпустил: иначе нажатие кнопки
 * через минуту после рестарта отвечало бы «запрос не найден», а игрок оставался бы
 * висеть в ожидании второго фактора до самого таймаута.
 */
public interface LoginApprovalRepository {

    @NotNull CompletableFuture<LoginApproval> insert(@NotNull LoginApproval approval);

    @NotNull CompletableFuture<Optional<LoginApproval>> findByPublicId(@NotNull String publicId);

    /** Находит веб-попытку по её публичной метке. Саму метку недостаточно предъявить
     * для обмена: это дополнительно защищает сохранённый только в вкладке proof. */
    @NotNull CompletableFuture<Optional<LoginApproval>> findByAttemptId(@NotNull String attemptId);

    /** Сохраняет сессию, созданную после нажатия кнопки, до её выдачи браузеру. */
    @NotNull CompletableFuture<Void> storeCompletedSession(@NotNull String publicId, long sessionId);

    /**
     * Атомарно погашает право вкладки получить уже созданную WEB-сессию.
     *
     * <p>Условие включает proof, решение, сессию и отметку погашения. Две вкладки
     * с одним proof не смогут получить пару токенов одновременно.
     */
    @NotNull CompletableFuture<ExchangeOutcome> consumeWebExchange(@NotNull String attemptId,
                                                                    @NotNull String proofHash,
                                                                    @NotNull Instant at);

    /**
     * Атомарно переводит подтверждение из {@link ApprovalStatus#PENDING} в решение.
     *
     * <p>Проверка «ещё ожидает и не истекло» и запись решения — один {@code UPDATE}.
     * Раздельные «прочитать и проверить» и «записать» оставляли окно, в которое
     * помещалось второе нажатие: обе кнопки видели {@code PENDING}, и вход завершался
     * дважды. Здесь второе нажатие изменит ноль строк и узнает об этом сразу.
     *
     * @param decidedBy идентификатор нажавшего в мессенджере — для аудита
     * @return состояние записи <em>после</em> попытки: при успехе оно равно
     *         {@code decision}, при проигрыше — тому решению, что уже записано
     */
    @NotNull CompletableFuture<DecisionOutcome> decide(@NotNull String publicId,
                                                       @NotNull ApprovalStatus decision,
                                                       @NotNull Instant at,
                                                       long decidedBy);

    /** Помечает просроченные подтверждения. Вызывается планировщиком. */
    @NotNull CompletableFuture<Integer> expireOverdue(@NotNull Instant at);

    /**
     * Гасит прежние ожидающие подтверждения того же игрока и невыданную WEB-попытку.
     *
     * <p>Новая попытка входа обесценивает предыдущую: иначе у игрока в чате копятся
     * кнопки, и нажатие старой завершало бы вход, которого уже нет. Уже одобренная,
     * но ещё не обменянная WEB-вкладка тоже гасится, поэтому две вкладки не могут
     * по очереди забрать две сессии из одного ряда параллельных попыток.
     *
     * @return число погашенных записей
     */
    @NotNull CompletableFuture<Integer> supersedePending(long accountId,
                                                          @NotNull String exceptPublicId,
                                                          @NotNull Instant at);

    @NotNull CompletableFuture<Integer> deleteDecidedBefore(@NotNull Instant before);

    /**
     * @param applied  {@code true}, если решение записано именно этим вызовом
     * @param approval состояние записи после попытки; {@code null}, если записи нет
     */
    record DecisionOutcome(boolean applied, @Nullable LoginApproval approval) {

        public static @NotNull DecisionOutcome missing() {
            return new DecisionOutcome(false, null);
        }
    }

    record ExchangeOutcome(boolean consumed, @Nullable LoginApproval approval) {
    }
}
