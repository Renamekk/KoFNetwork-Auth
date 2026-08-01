package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.CaptchaType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Выдача и проверка CAPTCHA.
 *
 * <p>Сервис не умеет рисовать: он владеет только логикой «выдать задачу — проверить
 * ответ». За отображение отвечает {@link CaptchaRenderer}, реализуемый в платформенном
 * модуле, — сервер Paper показывает GUI, а веб-API отдаёт JSON. Благодаря этому
 * добавление нового вида CAPTCHA не требует правки сервиса.
 */
public interface CaptchaService {

    /**
     * Нужна ли игроку CAPTCHA.
     *
     * <p>Требуется при первом входе и при подозрительной активности; снимается флагом
     * {@code users.captcha_passed} и правом {@code kofauth.bypass.captcha}.
     */
    @NotNull CompletableFuture<Boolean> isRequired(@Nullable Long accountId, @NotNull AuthContext context);

    /**
     * Выдаёт челлендж.
     *
     * @param type {@code null} — выбрать тип по конфигурации, повысив сложность,
     *             если по адресу уже фиксировались провалы
     */
    @NotNull CompletableFuture<CaptchaChallenge> issue(@Nullable Long accountId,
                                                       @NotNull UUID playerUuid,
                                                       @Nullable CaptchaType type,
                                                       @NotNull AuthContext context);

    /**
     * Проверяет ответ.
     *
     * <p>Сравнение хэшей выполняется в постоянном времени: ответ на CAPTCHA короткий,
     * и посимвольное сравнение с ранним выходом теоретически позволяет подобрать его
     * по времени отклика.
     */
    @NotNull CompletableFuture<CaptchaVerdict> verify(@NotNull String challengeId, @NotNull String answer);

    /**
     * Результат проверки.
     *
     * @param remainingAttempts сколько попыток осталось
     * @param challenge         обновлённый челлендж; {@code null}, если он не найден
     */
    record CaptchaVerdict(boolean passed,
                          boolean exhausted,
                          int remainingAttempts,
                          @Nullable CaptchaChallenge challenge) {

        public static @NotNull CaptchaVerdict pass(@NotNull CaptchaChallenge challenge) {
            return new CaptchaVerdict(true, false, challenge.remainingAttempts(), challenge);
        }

        public static @NotNull CaptchaVerdict fail(@NotNull CaptchaChallenge challenge) {
            return new CaptchaVerdict(false, challenge.remainingAttempts() == 0,
                    challenge.remainingAttempts(), challenge);
        }

        /** Челлендж не найден или уже истёк. */
        public static @NotNull CaptchaVerdict notFound() {
            return new CaptchaVerdict(false, true, 0, null);
        }
    }

    /** Незавершённый челлендж игрока. */
    @NotNull CompletableFuture<Optional<CaptchaChallenge>> findPending(@NotNull UUID playerUuid);

    /** Отменяет текущий челлендж — например, при отключении игрока. */
    @NotNull CompletableFuture<Void> cancel(@NotNull UUID playerUuid);

    /**
     * Регистрирует средство отображения.
     *
     * <p>Точка расширения по принципу открытости-закрытости: новый вид CAPTCHA
     * добавляется регистрацией рендерера, без изменений в этом интерфейсе.
     */
    void registerRenderer(@NotNull CaptchaRenderer renderer);

    /** Отображение CAPTCHA на конкретной платформе: GUI на Paper, JSON в веб-API, кнопки в боте. */
    interface CaptchaRenderer {

        /** Типы, которые умеет отображать этот рендерер. */
        @NotNull List<CaptchaType> supportedTypes();

        /**
         * Готовит содержимое задачи.
         *
         * <p><b>Рендерер получает правильный ответ.</b> Иначе он не может разложить
         * задачу так, чтобы она вообще имела решение: для сетки ответ — это номер
         * ячейки, и не зная его, рендерер разместит отвлекающие варианты во всех
         * ячейках, включая правильную. Игрок получит задачу без верного варианта.
         *
         * <p>Ответ не покидает процесс, который выдал задачу: {@link CaptchaChallenge}
         * хранит только его SHA-256, а открытое значение живёт в памяти сервиса
         * ровно столько, сколько длится задача.
         *
         * @param answer правильный ответ: номер ячейки для GUI, текст для ввода
         * @return набор данных для показа
         */
        @NotNull RenderedCaptcha render(@NotNull CaptchaChallenge challenge, @NotNull String answer);
    }

    /**
     * Готовое к показу содержимое задачи.
     *
     * @param prompt      текст задания
     * @param options     варианты ответа для GUI и кнопок; пусто для ввода текстом
     * @param imageBase64 изображение для {@link CaptchaType#MAP_IMAGE}; иначе {@code null}
     */
    record RenderedCaptcha(@NotNull String prompt,
                           @NotNull List<String> options,
                           @Nullable String imageBase64) {

        public RenderedCaptcha {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
