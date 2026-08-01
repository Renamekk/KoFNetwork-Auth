package net.kofnetwork.auth.api.dto;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Данные для подключения Google Authenticator.
 *
 * <p>Единственный момент, когда секрет и резервные коды существуют в открытом виде.
 * После этого ответа секрет хранится зашифрованным, а коды — только как SHA-256, и
 * повторно показать их невозможно: остаётся лишь перевыпустить.
 *
 * <p>Объект не логируется и не кэшируется.
 *
 * @param qrCodePngBase64 готовое изображение QR: генерировать его на клиенте означало бы
 *                        передать секрет ещё и в параметрах запроса к стороннему сервису
 */
public record TotpSetupDto(
        @NotNull String secret,
        @NotNull String otpauthUri,
        @NotNull String qrCodePngBase64,
        @NotNull List<String> recoveryCodes
) {

    public TotpSetupDto {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(otpauthUri, "otpauthUri");
        Objects.requireNonNull(qrCodePngBase64, "qrCodePngBase64");
        recoveryCodes = recoveryCodes == null ? List.of() : List.copyOf(recoveryCodes);
    }

    /** Всё содержимое скрыто: этот объект не должен попасть в лог ни при каких условиях. */
    @Override
    public String toString() {
        return "TotpSetupDto{secret=<redacted>, recoveryCodes=" + recoveryCodes.size() + " шт.}";
    }
}
