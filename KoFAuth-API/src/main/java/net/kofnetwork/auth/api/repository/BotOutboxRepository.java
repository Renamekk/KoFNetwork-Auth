package net.kofnetwork.auth.api.repository;

import net.kofnetwork.auth.api.model.BotMessage;
import net.kofnetwork.auth.api.model.BotPlatform;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Долговечная очередь сообщений ботам.
 *
 * <p>Доставка «не меньше одного раза»: бот читает начиная с курсора, обрабатывает и
 * подтверждает прочитанное. Упасть он может в любой момент между чтением и
 * подтверждением, поэтому одно и то же сообщение он обязан уметь обработать дважды.
 * Для запроса подтверждения входа это безопасно — решение принимается атомарно на
 * стороне сервера, а повторно показанная кнопка ведёт к тому же самому запросу.
 *
 * <p>Курсор хранится на сервере, а не у бота: контейнер бота не имеет состояния и
 * после перезапуска не помнил бы, до какого места дочитал.
 */
public interface BotOutboxRepository {

    @NotNull CompletableFuture<BotMessage> append(@NotNull BotMessage message);

    /**
     * Сообщения платформе после указанного курсора.
     *
     * @param after курсор: возвращаются сообщения строго с большим номером
     * @param limit верхняя граница выборки
     */
    @NotNull CompletableFuture<List<BotMessage>> readAfter(@NotNull BotPlatform platform,
                                                            long after,
                                                            int limit,
                                                            @NotNull Instant now);

    /** Текущий курсор платформы; {@code 0}, если бот ещё ничего не подтверждал. */
    @NotNull CompletableFuture<Long> cursor(@NotNull BotPlatform platform);

    /**
     * Двигает курсор вперёд.
     *
     * <p>Только вперёд: запоздавший ответ бота с меньшим значением не должен
     * заставлять систему заново рассылать уже обработанные сообщения.
     */
    @NotNull CompletableFuture<Long> acknowledge(@NotNull BotPlatform platform, long cursor);

    /** Удаляет отработавшие сообщения. Вызывается планировщиком. */
    @NotNull CompletableFuture<Integer> deleteExpired(@NotNull Instant before);
}
