package net.kofnetwork.auth.api.result;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationResultTest {

    @Test
    void успех_несёт_значение() {
        OperationResult<String> result = OperationResult.ok("значение");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("значение");
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void неуспех_несёт_код_и_сообщение() {
        OperationResult<String> result = OperationResult.fail("USERNAME_TAKEN", "ник Steve уже занят");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorCode()).isEqualTo("USERNAME_TAKEN");
        assertThat(result.errorMessage()).isEqualTo("ник Steve уже занят");
        assertThat(result.hasErrorCode("USERNAME_TAKEN")).isTrue();
        assertThat(result.hasErrorCode("OTHER")).isFalse();
    }

    @Test
    void обращение_к_значению_неуспешного_результата_бросает_исключение() {
        OperationResult<String> result = OperationResult.fail("TOKEN_EXPIRED", "код истёк");

        assertThatThrownBy(result::value)
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("TOKEN_EXPIRED");
    }

    @Test
    void orElse_возвращает_запасной_вариант_при_неуспехе() {
        assertThat(OperationResult.<String>fail("E").orElse("запасной")).isEqualTo("запасной");
        assertThat(OperationResult.ok("основной").orElse("запасной")).isEqualTo("основной");
    }

    @Test
    void map_преобразует_значение_успеха() {
        OperationResult<Integer> mapped = OperationResult.ok("текст").map(String::length);

        assertThat(mapped.value()).isEqualTo(5);
    }

    @Test
    void map_пропускает_неуспех_насквозь_сохраняя_код() {
        OperationResult<Integer> mapped = OperationResult.<String>fail("E_CODE", "деталь")
                .map(String::length);

        assertThat(mapped.isFailure()).isTrue();
        assertThat(mapped.errorCode()).isEqualTo("E_CODE");
        assertThat(mapped.errorMessage()).isEqualTo("деталь");
    }

    @Test
    void flatMap_соединяет_операции_прерываясь_на_первом_неуспехе() {
        OperationResult<String> result = OperationResult.ok("a")
                .flatMap(v -> OperationResult.ok(v + "b"))
                .flatMap(v -> OperationResult.<String>fail("STOP", "прервано"))
                .flatMap(v -> OperationResult.ok(v + "c"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorCode()).isEqualTo("STOP");
    }

    @Test
    void ifSuccess_и_ifFailure_вызываются_избирательно() {
        AtomicReference<String> onSuccess = new AtomicReference<>();
        AtomicReference<String> onFailure = new AtomicReference<>();

        OperationResult.ok("v").ifSuccess(onSuccess::set).ifFailure((c, m) -> onFailure.set(c));
        assertThat(onSuccess.get()).isEqualTo("v");
        assertThat(onFailure.get()).isNull();

        OperationResult.<String>fail("CODE").ifSuccess(onSuccess::set).ifFailure((c, m) -> onFailure.set(c));
        assertThat(onFailure.get()).isEqualTo("CODE");
    }

    @Test
    void успех_без_значения_допустим() {
        OperationResult<Void> result = OperationResult.ok();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.valueOrNull()).isNull();
    }

    @Test
    void propagateFailure_недопустим_на_успешном_результате() {
        assertThatThrownBy(() -> OperationResult.ok("v").propagateFailure())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toOptional_отражает_исход() {
        assertThat(OperationResult.ok("v").toOptional()).contains("v");
        assertThat(OperationResult.<String>fail("E").toOptional()).isEmpty();
    }
}
