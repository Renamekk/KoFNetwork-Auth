package net.kofnetwork.auth.api.exception;

/**
 * Базовое непроверяемое исключение платформы.
 *
 * <p>Исключения в KoFAuth зарезервированы за <em>сбоями инфраструктуры</em>: недоступна база,
 * не расшифровывается секрет, повреждена конфигурация. Ожидаемые исходы бизнес-логики
 * (неверный пароль, занятый ник, истёкший код) исключениями не являются и возвращаются
 * типами из пакета {@code net.kofnetwork.auth.api.result}.
 *
 * <p>Причина такого разделения прикладная: неверный пароль на входе — это норма, которая
 * случается тысячи раз в сутки. Строить на ней исключения означает платить за stack trace
 * в самом горячем пути и терять различие между «пользователь ошибся» и «база упала».
 */
public class KoFAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public KoFAuthException(String message) {
        super(message);
    }

    public KoFAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
