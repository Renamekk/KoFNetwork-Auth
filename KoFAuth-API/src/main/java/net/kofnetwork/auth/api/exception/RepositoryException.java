package net.kofnetwork.auth.api.exception;

/**
 * Сбой слоя доступа к данным: недоступна база, нарушено ограничение целостности,
 * истёк таймаут запроса.
 *
 * <p>Оборачивает {@link java.sql.SQLException}, чтобы сервисный слой не зависел от JDBC:
 * это позволяет заменить реализацию репозитория (например, на MongoDB для аналитики)
 * без правки сигнатур сервисов.
 */
public class RepositoryException extends KoFAuthException {

    private static final long serialVersionUID = 1L;

    public RepositoryException(String message) {
        super(message);
    }

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
