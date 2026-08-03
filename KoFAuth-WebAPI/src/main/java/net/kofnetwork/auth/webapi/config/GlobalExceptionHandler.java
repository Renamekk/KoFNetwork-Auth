package net.kofnetwork.auth.webapi.config;

import net.kofnetwork.auth.api.exception.ConfigurationException;
import net.kofnetwork.auth.api.exception.RepositoryException;
import net.kofnetwork.auth.webapi.dto.ApiDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Единая обработка ошибок.
 *
 * <p><b>Наружу не уходят технические детали.</b> Текст SQL-ошибки или стек
 * рассказывают о внутреннем устройстве больше, чем следует: имена таблиц, версию
 * СУБД, структуру запросов. Клиент получает код и нейтральное сообщение,
 * подробности остаются в логе сервера.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Ошибки валидации — единственный случай, когда подробности полезны клиенту. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> onValidation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiDtos.ErrorResponse.of("VALIDATION_FAILED", details));
    }

    /**
     * Несуществующий путь.
     *
     * <p>Без этого обработчика запрос к опечатанному адресу попадал в
     * {@link #onAny(Exception)} и возвращал 500. Разница не косметическая:
     * пятисотые поднимают тревогу в мониторинге и отправляют разбираться с
     * «отказом сервиса» там, где клиент просто ошибся адресом. В лог такое
     * пишется на уровне отладки — сканеры перебирают пути постоянно,
     * и предупреждение на каждый из них скрывает настоящие ошибки.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiDtos.ErrorResponse> onNotFound(Exception e) {
        LOGGER.debug("Запрошен несуществующий путь: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiDtos.ErrorResponse.of("NOT_FOUND", "Адрес не найден"));
    }

    @ExceptionHandler(RepositoryException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> onRepository(RepositoryException e) {
        LOGGER.error("Отказ хранилища", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiDtos.ErrorResponse.of("STORAGE_UNAVAILABLE",
                        "Сервис временно недоступен"));
    }

    @ExceptionHandler(ConfigurationException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> onConfiguration(ConfigurationException e) {
        LOGGER.error("Ошибка конфигурации", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiDtos.ErrorResponse.of("MISCONFIGURED", "Сервис настроен неверно"));
    }

    /**
     * Асинхронные сбои.
     *
     * <p>{@code join()} заворачивает исходное исключение в {@link CompletionException};
     * без разворачивания в лог попал бы бесполезный внешний слой вместо причины.
     */
    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> onCompletion(CompletionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof RepositoryException repository) {
            return onRepository(repository);
        }
        LOGGER.error("Необработанный сбой асинхронной операции", cause);
        return internalError();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiDtos.ErrorResponse> onAny(Exception e) {
        LOGGER.error("Необработанное исключение", e);
        return internalError();
    }

    private static ResponseEntity<ApiDtos.ErrorResponse> internalError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiDtos.ErrorResponse.of("INTERNAL_ERROR", "Внутренняя ошибка"));
    }
}
