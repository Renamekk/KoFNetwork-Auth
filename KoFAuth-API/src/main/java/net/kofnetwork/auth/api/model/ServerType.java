package net.kofnetwork.auth.api.model;

/** Роль сервера в сети. Соответствует {@code servers.type}. */
public enum ServerType {

    /** Velocity-прокси. */
    PROXY,

    /** Limbo: сюда попадает игрок до аутентификации. */
    LIMBO,

    /** Лобби: точка входа после успешного входа. */
    LOBBY,

    /** Игровой сервер. */
    GAME;

    /** Разрешено ли попадать на сервер до аутентификации. */
    public boolean allowsUnauthenticated() {
        return this == LIMBO;
    }
}
