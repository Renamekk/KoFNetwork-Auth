package net.kofnetwork.auth.webapi.security;

import org.jetbrains.annotations.NotNull;

/**
 * Опознанный владелец запроса.
 *
 * <p>Кладётся фильтром в атрибут запроса и достаётся контроллерами. Отдельный тип
 * вместо голого {@code long accountId} затем, чтобы контроллер не мог случайно
 * принять за идентификатор аккаунта что-то другое — например, значение из тела
 * запроса, которым управляет клиент.
 */
public record AuthenticatedUser(long accountId,
                                @NotNull String username,
                                @NotNull String sessionPublicId) {

    /** Имя атрибута запроса. */
    public static final String ATTRIBUTE = "kofauth.user";
}
