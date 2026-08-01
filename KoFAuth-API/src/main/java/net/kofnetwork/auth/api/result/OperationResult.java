package net.kofnetwork.auth.api.result;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Результат операции: либо значение, либо код ошибки с сообщением.
 *
 * <p>Заменяет исключения там, где неуспех — штатный исход. «Ник занят», «код истёк»,
 * «пароль слишком простой» происходят постоянно и по вине пользователя, а не системы;
 * поднимать на них стек вызовов дорого и неинформативно.
 *
 * <p>Отличие от {@link Optional}: {@code Optional.empty()} не объясняет, почему пусто.
 * Здесь причина обязательна — {@link #errorCode()} используется для выбора сообщения
 * из локализации, а {@link #errorMessage()} попадает в лог.
 *
 * @param <T> тип значения при успехе
 */
public final class OperationResult<T> {

    private static final OperationResult<Void> OK_VOID = new OperationResult<>(true, null, null, null);

    private final boolean success;
    private final T value;
    private final String errorCode;
    private final String errorMessage;

    private OperationResult(boolean success, T value, String errorCode, String errorMessage) {
        this.success = success;
        this.value = value;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    // ------------------------------------------------------------------ конструирование

    /** Успех со значением. */
    @Contract("_ -> new")
    public static <T> @NotNull OperationResult<T> ok(@NotNull T value) {
        return new OperationResult<>(true, Objects.requireNonNull(value, "value"), null, null);
    }

    /** Успех без значения. */
    @SuppressWarnings("unchecked")
    public static <T> @NotNull OperationResult<T> ok() {
        return (OperationResult<T>) OK_VOID;
    }

    /**
     * Неуспех.
     *
     * @param errorCode    машинный код: {@code USERNAME_TAKEN}, {@code TOKEN_EXPIRED}.
     *                     По нему выбирается сообщение игроку — сам код наружу не показывается
     * @param errorMessage подробность для журнала; может содержать технические детали
     */
    @Contract("_, _ -> new")
    public static <T> @NotNull OperationResult<T> fail(@NotNull String errorCode, @NotNull String errorMessage) {
        return new OperationResult<>(false,
                null,
                Objects.requireNonNull(errorCode, "errorCode"),
                Objects.requireNonNull(errorMessage, "errorMessage"));
    }

    /** Неуспех, где код и сообщение совпадают. */
    public static <T> @NotNull OperationResult<T> fail(@NotNull String errorCode) {
        return fail(errorCode, errorCode);
    }

    /** Переносит неуспех на другой тип значения, сохраняя код и сообщение. */
    @SuppressWarnings("unchecked")
    public <R> @NotNull OperationResult<R> propagateFailure() {
        if (success) {
            throw new IllegalStateException("propagateFailure вызван на успешном результате");
        }
        return (OperationResult<R>) this;
    }

    // ------------------------------------------------------------------ доступ

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    /**
     * Значение успешного результата.
     *
     * @throws NoSuchElementException если результат неуспешен или значения нет
     */
    public @NotNull T value() {
        if (!success) {
            throw new NoSuchElementException("Результат неуспешен: " + errorCode + " (" + errorMessage + ")");
        }
        if (value == null) {
            throw new NoSuchElementException("Успешный результат без значения");
        }
        return value;
    }

    /** Значение или {@code null}. */
    public @Nullable T valueOrNull() {
        return success ? value : null;
    }

    /** Значение или запасной вариант. */
    public T orElse(T fallback) {
        return success && value != null ? value : fallback;
    }

    public T orElseGet(@NotNull Supplier<? extends T> fallback) {
        return success && value != null ? value : fallback.get();
    }

    /** Код ошибки. {@code null} при успехе. */
    public @Nullable String errorCode() {
        return errorCode;
    }

    /** Подробность ошибки. {@code null} при успехе. */
    public @Nullable String errorMessage() {
        return errorMessage;
    }

    /** Совпадает ли код ошибки с указанным. */
    public boolean hasErrorCode(@NotNull String code) {
        return !success && code.equals(errorCode);
    }

    public @NotNull Optional<T> toOptional() {
        return success ? Optional.ofNullable(value) : Optional.empty();
    }

    // ------------------------------------------------------------------ композиция

    /** Преобразует значение успешного результата; неуспех проходит насквозь. */
    public <R> @NotNull OperationResult<R> map(@NotNull Function<? super T, ? extends R> mapper) {
        if (!success) {
            return propagateFailure();
        }
        return value == null ? OperationResult.ok() : OperationResult.ok(mapper.apply(value));
    }

    /** Цепочка операций, каждая из которых может завершиться неуспехом. */
    public <R> @NotNull OperationResult<R> flatMap(
            @NotNull Function<? super T, OperationResult<R>> mapper) {
        if (!success) {
            return propagateFailure();
        }
        return mapper.apply(value);
    }

    /** Выполняет действие при успехе. Возвращает себя — удобно для цепочек. */
    public @NotNull OperationResult<T> ifSuccess(@NotNull Consumer<? super T> action) {
        if (success && value != null) {
            action.accept(value);
        }
        return this;
    }

    /** Выполняет действие при неуспехе, получая код и сообщение. */
    public @NotNull OperationResult<T> ifFailure(@NotNull java.util.function.BiConsumer<String, String> action) {
        if (!success) {
            action.accept(errorCode, errorMessage);
        }
        return this;
    }

    @Override
    public String toString() {
        return success
                ? "OperationResult{ok, value=" + value + '}'
                : "OperationResult{fail, code=" + errorCode + ", message=" + errorMessage + '}';
    }
}
