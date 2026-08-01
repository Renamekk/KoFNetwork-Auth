package net.kofnetwork.auth.api.dto;

import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.AccountStatus;
import net.kofnetwork.auth.api.model.TwoFactorMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Профиль аккаунта для внешнего мира: REST API, личный кабинет, боты.
 *
 * <p>Существует именно затем, чтобы {@link Account} не покидал границу процесса.
 * Доменная сущность содержит {@code passwordHash} и полный IP; отдать её в JSON —
 * значит рано или поздно отдать и их. Здесь хэша нет вовсе, а адрес присутствует
 * только в маскированном виде.
 *
 * @param lastLoginIpMasked последний адрес в виде {@code 192.168.0.***}
 * @param roles             машинные имена ролей
 */
public record AccountProfileDto(
        @NotNull UUID uuid,
        @NotNull String username,
        @NotNull AccountStatus status,
        boolean premium,
        @NotNull Instant registrationDate,
        @Nullable Instant lastLoginAt,
        @Nullable String lastLoginIpMasked,
        @Nullable String lastCountry,
        @Nullable String lastCity,
        @Nullable String lastServer,
        @NotNull Set<TwoFactorMethod> twoFactorMethods,
        boolean emailLinked,
        boolean emailVerified,
        boolean telegramLinked,
        boolean discordLinked,
        boolean totpEnabled,
        int activeSessions,
        int knownDevices,
        @NotNull List<String> roles
) {

    public AccountProfileDto {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(registrationDate, "registrationDate");
        twoFactorMethods = twoFactorMethods == null ? Set.of() : Set.copyOf(twoFactorMethods);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /**
     * Собирает профиль из доменной сущности и агрегатов, посчитанных сервисным слоем.
     *
     * <p>Флаги привязок передаются параметрами, а не читаются из {@link Account}: аккаунт
     * не знает о своих привязках, они лежат в отдельных таблицах, и подтягивать их
     * внутри модели означало бы спрятать в геттере запрос к базе.
     */
    public static @NotNull AccountProfileDto from(@NotNull Account account,
                                                  boolean emailLinked,
                                                  boolean emailVerified,
                                                  boolean telegramLinked,
                                                  boolean discordLinked,
                                                  boolean totpEnabled,
                                                  int activeSessions,
                                                  int knownDevices,
                                                  @NotNull List<String> roles) {
        return new AccountProfileDto(
                account.uuid(),
                account.username(),
                account.status(),
                account.premium(),
                account.registrationDate(),
                account.lastLoginAt(),
                account.lastLoginIp() == null ? null : account.lastLoginIp().asMasked(),
                account.lastCountry(),
                account.lastCity(),
                account.lastServer(),
                account.twoFactorMethods(),
                emailLinked,
                emailVerified,
                telegramLinked,
                discordLinked,
                totpEnabled,
                activeSessions,
                knownDevices,
                roles);
    }

    /**
     * Оценка защищённости аккаунта, 0..100. Показывается в личном кабинете, чтобы
     * подсказать игроку следующий полезный шаг.
     */
    public int securityScore() {
        int score = 20; // за сам факт наличия пароля
        if (emailVerified) {
            score += 20;
        } else if (emailLinked) {
            score += 5;
        }
        if (totpEnabled) {
            score += 35;
        }
        if (telegramLinked) {
            score += 15;
        }
        if (discordLinked) {
            score += 10;
        }
        return Math.min(100, score);
    }
}
