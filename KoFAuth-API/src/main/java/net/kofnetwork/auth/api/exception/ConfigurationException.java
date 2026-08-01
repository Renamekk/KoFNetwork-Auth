package net.kofnetwork.auth.api.exception;

/**
 * Конфигурация некорректна или не может быть прочитана.
 *
 * <p>Бросается на старте (и при {@code /auth reload}) до того, как компонент начнёт работу.
 * Принцип fail-fast здесь важнее живучести: сервер, стартовавший с пустым ключом AES или
 * с cost BCrypt равным 4, опаснее сервера, который не стартовал вовсе.
 */
public class ConfigurationException extends KoFAuthException {

    private static final long serialVersionUID = 1L;

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
