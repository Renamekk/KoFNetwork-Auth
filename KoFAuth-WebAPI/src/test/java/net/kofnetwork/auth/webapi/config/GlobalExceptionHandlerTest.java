package net.kofnetwork.auth.webapi.config;

import net.kofnetwork.auth.api.exception.RepositoryException;
import net.kofnetwork.auth.webapi.dto.ApiDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

/** Отображение исключений на коды ответа. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("несуществующий путь — это 404, а не 500")
    void несуществующий_путь_отдаёт_404() {
        // Пятисотая на опечатку в адресе поднимает тревогу в мониторинге и
        // отправляет дежурного искать отказ сервиса там, где его нет.
        ResponseEntity<ApiDtos.ErrorResponse> response =
                handler.onNotFound(new NoResourceFoundException(HttpMethod.GET, "/api/нет-такого"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("отказ хранилища — 503, детали наружу не уходят")
    void отказ_хранилища_отдаёт_503_без_подробностей() {
        ResponseEntity<ApiDtos.ErrorResponse> response = handler.onRepository(
                new RepositoryException("Duplicate entry 'abc' for key 'tokens.uk_tokens_hash'"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("STORAGE_UNAVAILABLE");
        // Имя таблицы и текст ошибки СУБД — подсказка тому, кто изучает систему
        // снаружи. Клиенту достаточно кода.
        assertThat(response.getBody().message()).doesNotContain("tokens");
    }

    @Test
    @DisplayName("асинхронный сбой разворачивается до причины")
    void асинхронный_сбой_хранилища_отдаёт_503() {
        // join() заворачивает исходное исключение в CompletionException. Без
        // разворачивания отказ базы выглядел бы как внутренняя ошибка, и по коду
        // ответа нельзя было бы отличить «база недоступна» от «ошибка в коде».
        ResponseEntity<ApiDtos.ErrorResponse> response = handler.onCompletion(
                new CompletionException(new RepositoryException("база недоступна")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("STORAGE_UNAVAILABLE");
    }
}
