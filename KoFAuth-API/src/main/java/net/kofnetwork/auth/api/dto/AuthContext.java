package net.kofnetwork.auth.api.dto;

import net.kofnetwork.auth.api.model.DevicePlatform;
import net.kofnetwork.auth.api.model.EventSource;
import net.kofnetwork.auth.api.model.IpAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Контекст запроса: откуда пришло действие.
 *
 * <p>Единый объект вместо россыпи параметров {@code (ip, userAgent, source, ...)} в каждой
 * сигнатуре. Практический смысл — не в краткости: контекст нужен почти всем сервисам
 * (rate-limit считает по IP, аудит пишет источник, распознавание устройства строит
 * отпечаток), и передача его целиком гарантирует, что при добавлении нового поля не
 * придётся править два десятка сигнатур.
 *
 * <p>Геоданные ({@link #country()}, {@link #city()}) заполняются не всегда: их получение
 * требует обращения к внешней службе, и в горячем пути входа оно выполняется асинхронно
 * уже после ответа игроку.
 *
 * @param deviceFingerprint SHA-256 характеристик клиента; {@code null}, если платформа
 *                          не даёт достаточно данных для устойчивого отпечатка
 */
public record AuthContext(
        @NotNull IpAddress ip,
        @NotNull EventSource source,
        @NotNull DevicePlatform platform,
        @Nullable String userAgent,
        @Nullable String deviceFingerprint,
        @Nullable String country,
        @Nullable String city,
        @Nullable String isp,
        @Nullable String server,
        @Nullable Integer protocolVersion,
        @Nullable String clientBrand
) {

    public AuthContext {
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(platform, "platform");
    }

    /** Контекст подключения из Minecraft. */
    public static @NotNull AuthContext minecraft(@NotNull IpAddress ip,
                                                 @Nullable String server,
                                                 @Nullable Integer protocolVersion,
                                                 @Nullable String clientBrand) {
        return new AuthContext(ip, EventSource.MINECRAFT, DevicePlatform.MINECRAFT,
                clientBrand, null, null, null, null, server, protocolVersion, clientBrand);
    }

    /** Контекст HTTP-запроса. */
    public static @NotNull AuthContext web(@NotNull IpAddress ip, @Nullable String userAgent) {
        return new AuthContext(ip, EventSource.WEB, DevicePlatform.WEB,
                userAgent, null, null, null, null, null, null, null);
    }

    /** Контекст сообщения Telegram. */
    public static @NotNull AuthContext telegram() {
        return new AuthContext(IpAddress.UNKNOWN, EventSource.TELEGRAM, DevicePlatform.TELEGRAM,
                "Telegram", null, null, null, null, null, null, null);
    }

    /** Контекст взаимодействия в Discord. */
    public static @NotNull AuthContext discord() {
        return new AuthContext(IpAddress.UNKNOWN, EventSource.DISCORD, DevicePlatform.DISCORD,
                "Discord", null, null, null, null, null, null, null);
    }

    /**
     * Контекст внутреннего действия: планировщик, миграция, консольная команда.
     * Не подпадает под rate-limit и проверки AntiBot.
     */
    public static @NotNull AuthContext system() {
        return new AuthContext(IpAddress.UNKNOWN, EventSource.SYSTEM, DevicePlatform.UNKNOWN,
                "system", null, null, null, null, null, null, null);
    }

    /** Дополняет контекст геоданными, полученными асинхронно. */
    public @NotNull AuthContext withGeo(@Nullable String newCountry,
                                        @Nullable String newCity,
                                        @Nullable String newIsp) {
        return new AuthContext(ip, source, platform, userAgent, deviceFingerprint,
                newCountry, newCity, newIsp, server, protocolVersion, clientBrand);
    }

    public @NotNull AuthContext withDeviceFingerprint(@Nullable String fingerprint) {
        return new AuthContext(ip, source, platform, userAgent, fingerprint,
                country, city, isp, server, protocolVersion, clientBrand);
    }

    public @NotNull AuthContext withServer(@Nullable String newServer) {
        return new AuthContext(ip, source, platform, userAgent, deviceFingerprint,
                country, city, isp, newServer, protocolVersion, clientBrand);
    }

    /**
     * Подпадает ли запрос под ограничения скорости и проверки AntiBot.
     *
     * <p>Внутренние действия и запросы без внешнего адреса освобождаются: применять к
     * планировщику лимит «5 попыток входа в минуту» бессмысленно, а IP у него нет.
     */
    public boolean isSubjectToRateLimit() {
        return source != EventSource.SYSTEM && !ip.isUnknown();
    }
}
