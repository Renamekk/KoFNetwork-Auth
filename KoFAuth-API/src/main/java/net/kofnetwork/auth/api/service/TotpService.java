package net.kofnetwork.auth.api.service;

import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.dto.TotpSetupDto;
import net.kofnetwork.auth.api.result.OperationResult;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Google Authenticator и совместимые приложения.
 *
 * <p>Подключение двухшаговое: {@link #beginSetup} выдаёт секрет и QR, но второй фактор
 * ещё не работает; {@link #confirmSetup} включает его после ввода верного кода. Без
 * второго шага игрок, ошибившийся при сканировании QR, оказался бы заперт.
 */
public interface TotpService {

    /**
     * Начинает подключение: генерирует секрет, QR-код и резервные коды.
     *
     * <p>Единственный момент, когда секрет и коды существуют открытыми. Повторный вызов
     * до подтверждения перезаписывает предыдущий секрет — так игрок может начать заново,
     * если потерял QR.
     */
    @NotNull CompletableFuture<OperationResult<TotpSetupDto>> beginSetup(long accountId,
                                                                         @NotNull String username,
                                                                         @NotNull AuthContext context);

    /**
     * Подтверждает подключение вводом кода и включает второй фактор.
     *
     * @param code шестизначный код из приложения
     */
    @NotNull CompletableFuture<OperationResult<Void>> confirmSetup(long accountId,
                                                                   @NotNull String code,
                                                                   @NotNull AuthContext context);

    /**
     * Проверяет код.
     *
     * <p>Допускается расхождение часов на одно временное окно в обе стороны: часы на
     * телефоне игрока редко идеально синхронны, и жёсткая проверка отвергала бы верные
     * коды. Более широкое окно уже заметно расширяет пространство перебора.
     *
     * <p>Принятое окно фиксируется атомарно, поэтому один и тот же код нельзя
     * использовать дважды.
     *
     * @param code шестизначный код или резервный код
     */
    @NotNull CompletableFuture<Boolean> verify(long accountId, @NotNull String code);

    /**
     * Отключает второй фактор.
     *
     * <p>Требует подтверждения кодом или паролем: отключение 2FA — самая ценная для
     * злоумышленника операция, и разрешать её по одной лишь действующей сессии значит
     * обесценить сам второй фактор.
     */
    @NotNull CompletableFuture<OperationResult<Void>> disable(long accountId,
                                                              @NotNull String confirmation,
                                                              @NotNull AuthContext context);

    /**
     * Перевыпускает резервные коды, гася старые.
     *
     * @return новые коды в открытом виде — показываются один раз
     */
    @NotNull CompletableFuture<OperationResult<List<String>>> regenerateRecoveryCodes(long accountId,
                                                                                      @NotNull AuthContext context);

    /** Сколько резервных кодов осталось неиспользованными. */
    @NotNull CompletableFuture<Integer> countRemainingRecoveryCodes(long accountId);

    /** Включён ли TOTP у аккаунта. */
    @NotNull CompletableFuture<Boolean> isEnabled(long accountId);
}
