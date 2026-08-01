package net.kofnetwork.auth.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Аккаунт игрока — центральная сущность системы. Соответствует строке таблицы {@code users}.
 *
 * <p>Объект неизменяемый. Изменение выражается созданием нового экземпляра через
 * {@link #toBuilder()}; репозиторий сохраняет результат. Это дороже по аллокациям,
 * чем сеттеры, но снимает целый класс ошибок: один и тот же {@code Account} читается
 * одновременно с прокси, лимбо и веб-API, и мутабельный объект в этой схеме
 * потребовал бы синхронизации на каждом обращении.
 *
 * <p><b>Хэш пароля.</b> Поле {@link #passwordHash()} присутствует, потому что это часть
 * строки таблицы, но наружу оно не уходит никогда: DTO для REST и ботов
 * ({@code AccountProfileDto}) его не содержит, а {@link #toString()} печатает заглушку.
 *
 * @see net.kofnetwork.auth.api.dto.AccountProfileDto представление для внешнего мира
 */
public final class Account {

    /** Значение {@link #id()} для аккаунта, ещё не записанного в базу. */
    public static final long UNSAVED_ID = 0L;

    private final long id;
    private final UUID uuid;
    private final String username;
    private final String lowerUsername;

    private final String passwordHash;
    private final String passwordAlgorithm;
    private final Instant passwordUpdatedAt;

    private final AccountStatus status;
    private final boolean premium;

    private final IpAddress registrationIp;
    private final Instant registrationDate;

    private final IpAddress lastLoginIp;
    private final Instant lastLoginAt;
    private final Instant lastLogoutAt;
    private final String lastServer;
    private final String lastCountry;
    private final String lastCity;
    private final String lastUserAgent;

    private final int failedLoginAttempts;
    private final Instant lockedUntil;
    private final boolean captchaPassed;

    private final Set<TwoFactorMethod> twoFactorMethods;

    private final Instant createdAt;
    private final Instant updatedAt;

    private Account(Builder b) {
        this.id = b.id;
        this.uuid = Objects.requireNonNull(b.uuid, "uuid");
        this.username = Objects.requireNonNull(b.username, "username");
        this.lowerUsername = b.lowerUsername != null
                ? b.lowerUsername
                : b.username.toLowerCase(Locale.ROOT);
        this.passwordHash = Objects.requireNonNull(b.passwordHash, "passwordHash");
        this.passwordAlgorithm = b.passwordAlgorithm != null ? b.passwordAlgorithm : "BCRYPT";
        this.passwordUpdatedAt = b.passwordUpdatedAt;
        this.status = b.status != null ? b.status : AccountStatus.ACTIVE;
        this.premium = b.premium;
        this.registrationIp = b.registrationIp != null ? b.registrationIp : IpAddress.UNKNOWN;
        this.registrationDate = b.registrationDate != null ? b.registrationDate : Instant.now();
        this.lastLoginIp = b.lastLoginIp;
        this.lastLoginAt = b.lastLoginAt;
        this.lastLogoutAt = b.lastLogoutAt;
        this.lastServer = b.lastServer;
        this.lastCountry = b.lastCountry;
        this.lastCity = b.lastCity;
        this.lastUserAgent = b.lastUserAgent;
        this.failedLoginAttempts = b.failedLoginAttempts;
        this.lockedUntil = b.lockedUntil;
        this.captchaPassed = b.captchaPassed;
        this.twoFactorMethods = b.twoFactorMethods.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(b.twoFactorMethods));
        this.createdAt = b.createdAt != null ? b.createdAt : this.registrationDate;
        this.updatedAt = b.updatedAt != null ? b.updatedAt : this.createdAt;
    }

    // ------------------------------------------------------------------ доступ к полям

    /** Первичный ключ. {@link #UNSAVED_ID}, если аккаунт ещё не сохранён. */
    public long id() {
        return id;
    }

    public @NotNull UUID uuid() {
        return uuid;
    }

    /** Ник в том регистре, в котором игрок зарегистрировался. */
    public @NotNull String username() {
        return username;
    }

    /** Ник в нижнем регистре — ключ поиска. */
    public @NotNull String lowerUsername() {
        return lowerUsername;
    }

    /** Хэш пароля. Не передавать наружу процесса. */
    public @NotNull String passwordHash() {
        return passwordHash;
    }

    /** Алгоритм хэширования: {@code BCRYPT}. Поле нужно для будущего перехода на Argon2id. */
    public @NotNull String passwordAlgorithm() {
        return passwordAlgorithm;
    }

    public @Nullable Instant passwordUpdatedAt() {
        return passwordUpdatedAt;
    }

    public @NotNull AccountStatus status() {
        return status;
    }

    /** Лицензионный аккаунт: пароль не запрашивается, доверяем проверке Mojang. */
    public boolean premium() {
        return premium;
    }

    public @NotNull IpAddress registrationIp() {
        return registrationIp;
    }

    public @NotNull Instant registrationDate() {
        return registrationDate;
    }

    public @Nullable IpAddress lastLoginIp() {
        return lastLoginIp;
    }

    public @Nullable Instant lastLoginAt() {
        return lastLoginAt;
    }

    public @Nullable Instant lastLogoutAt() {
        return lastLogoutAt;
    }

    public @Nullable String lastServer() {
        return lastServer;
    }

    /** ISO 3166-1 alpha-2, например {@code RU}. */
    public @Nullable String lastCountry() {
        return lastCountry;
    }

    public @Nullable String lastCity() {
        return lastCity;
    }

    public @Nullable String lastUserAgent() {
        return lastUserAgent;
    }

    /** Неудачных попыток подряд с момента последнего успешного входа. */
    public int failedLoginAttempts() {
        return failedLoginAttempts;
    }

    /** До какого момента действует временная блокировка. {@code null} — блокировки не было. */
    public @Nullable Instant lockedUntil() {
        return lockedUntil;
    }

    /** Проходил ли игрок CAPTCHA хотя бы раз. */
    public boolean captchaPassed() {
        return captchaPassed;
    }

    /** Включённые вторые факторы. Пустое множество — 2FA выключена. */
    public @NotNull Set<TwoFactorMethod> twoFactorMethods() {
        return twoFactorMethods;
    }

    public @NotNull Instant createdAt() {
        return createdAt;
    }

    public @NotNull Instant updatedAt() {
        return updatedAt;
    }

    // ------------------------------------------------------------------ производные признаки

    /** Записан ли аккаунт в базу. */
    public boolean isPersisted() {
        return id != UNSAVED_ID;
    }

    /**
     * Действует ли временная блокировка на указанный момент.
     *
     * <p>Момент передаётся параметром, а не берётся из {@code Instant.now()}, чтобы
     * поведение было воспроизводимым в тестах и одинаковым для всех проверок в рамках
     * одной попытки входа.
     */
    public boolean isTemporarilyLocked(@NotNull Instant at) {
        return lockedUntil != null && lockedUntil.isAfter(at);
    }

    /** Разрешён ли вход: статус активен и временная блокировка не действует. */
    public boolean canLogin(@NotNull Instant at) {
        return status.allowsLogin() && !isTemporarilyLocked(at);
    }

    /** Включён ли хотя бы один второй фактор. */
    public boolean hasTwoFactor() {
        return !twoFactorMethods.isEmpty();
    }

    public boolean hasTwoFactor(@NotNull TwoFactorMethod method) {
        return twoFactorMethods.contains(method);
    }

    /**
     * Предпочтительный второй фактор: самый быстрый из включённых.
     *
     * @return метод или {@code null}, если 2FA выключена
     * @see TwoFactorMethod#priority()
     */
    public @Nullable TwoFactorMethod preferredTwoFactor() {
        return twoFactorMethods.stream()
                .max(java.util.Comparator.comparingInt(TwoFactorMethod::priority))
                .orElse(null);
    }

    /** Первый ли это вход (аккаунт создан, но ни разу не входил). */
    public boolean isFirstLogin() {
        return lastLoginAt == null;
    }

    // ------------------------------------------------------------------ builder

    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Создаёт заготовку для нового аккаунта. Поля активности остаются пустыми —
     * их заполнит первый успешный вход.
     */
    public static @NotNull Builder newAccount(@NotNull UUID uuid,
                                              @NotNull String username,
                                              @NotNull String passwordHash,
                                              @NotNull IpAddress registrationIp) {
        Instant now = Instant.now();
        return builder()
                .id(UNSAVED_ID)
                .uuid(uuid)
                .username(username)
                .passwordHash(passwordHash)
                .passwordUpdatedAt(now)
                .status(AccountStatus.ACTIVE)
                .registrationIp(registrationIp)
                .registrationDate(now)
                .createdAt(now)
                .updatedAt(now);
    }

    /** Копия строителя с текущими значениями — основа для «изменения» аккаунта. */
    public @NotNull Builder toBuilder() {
        return new Builder()
                .id(id)
                .uuid(uuid)
                .username(username)
                .lowerUsername(lowerUsername)
                .passwordHash(passwordHash)
                .passwordAlgorithm(passwordAlgorithm)
                .passwordUpdatedAt(passwordUpdatedAt)
                .status(status)
                .premium(premium)
                .registrationIp(registrationIp)
                .registrationDate(registrationDate)
                .lastLoginIp(lastLoginIp)
                .lastLoginAt(lastLoginAt)
                .lastLogoutAt(lastLogoutAt)
                .lastServer(lastServer)
                .lastCountry(lastCountry)
                .lastCity(lastCity)
                .lastUserAgent(lastUserAgent)
                .failedLoginAttempts(failedLoginAttempts)
                .lockedUntil(lockedUntil)
                .captchaPassed(captchaPassed)
                .twoFactorMethods(twoFactorMethods)
                .createdAt(createdAt)
                .updatedAt(updatedAt);
    }

    /** Строитель {@link Account}. Не потокобезопасен — предназначен для локального использования. */
    public static final class Builder {

        private long id = UNSAVED_ID;
        private UUID uuid;
        private String username;
        private String lowerUsername;
        private String passwordHash;
        private String passwordAlgorithm;
        private Instant passwordUpdatedAt;
        private AccountStatus status = AccountStatus.ACTIVE;
        private boolean premium;
        private IpAddress registrationIp;
        private Instant registrationDate;
        private IpAddress lastLoginIp;
        private Instant lastLoginAt;
        private Instant lastLogoutAt;
        private String lastServer;
        private String lastCountry;
        private String lastCity;
        private String lastUserAgent;
        private int failedLoginAttempts;
        private Instant lockedUntil;
        private boolean captchaPassed;
        private Set<TwoFactorMethod> twoFactorMethods = EnumSet.noneOf(TwoFactorMethod.class);
        private Instant createdAt;
        private Instant updatedAt;

        private Builder() {
        }

        public Builder id(long value) {
            this.id = value;
            return this;
        }

        public Builder uuid(UUID value) {
            this.uuid = value;
            return this;
        }

        public Builder username(String value) {
            this.username = value;
            return this;
        }

        public Builder lowerUsername(String value) {
            this.lowerUsername = value;
            return this;
        }

        public Builder passwordHash(String value) {
            this.passwordHash = value;
            return this;
        }

        public Builder passwordAlgorithm(String value) {
            this.passwordAlgorithm = value;
            return this;
        }

        public Builder passwordUpdatedAt(Instant value) {
            this.passwordUpdatedAt = value;
            return this;
        }

        public Builder status(AccountStatus value) {
            this.status = value;
            return this;
        }

        public Builder premium(boolean value) {
            this.premium = value;
            return this;
        }

        public Builder registrationIp(IpAddress value) {
            this.registrationIp = value;
            return this;
        }

        public Builder registrationDate(Instant value) {
            this.registrationDate = value;
            return this;
        }

        public Builder lastLoginIp(IpAddress value) {
            this.lastLoginIp = value;
            return this;
        }

        public Builder lastLoginAt(Instant value) {
            this.lastLoginAt = value;
            return this;
        }

        public Builder lastLogoutAt(Instant value) {
            this.lastLogoutAt = value;
            return this;
        }

        public Builder lastServer(String value) {
            this.lastServer = value;
            return this;
        }

        public Builder lastCountry(String value) {
            this.lastCountry = value;
            return this;
        }

        public Builder lastCity(String value) {
            this.lastCity = value;
            return this;
        }

        public Builder lastUserAgent(String value) {
            this.lastUserAgent = value;
            return this;
        }

        public Builder failedLoginAttempts(int value) {
            this.failedLoginAttempts = value;
            return this;
        }

        public Builder lockedUntil(Instant value) {
            this.lockedUntil = value;
            return this;
        }

        public Builder captchaPassed(boolean value) {
            this.captchaPassed = value;
            return this;
        }

        public Builder twoFactorMethods(Set<TwoFactorMethod> value) {
            this.twoFactorMethods = value == null || value.isEmpty()
                    ? EnumSet.noneOf(TwoFactorMethod.class)
                    : EnumSet.copyOf(value);
            return this;
        }

        public Builder addTwoFactorMethod(TwoFactorMethod value) {
            EnumSet<TwoFactorMethod> copy = EnumSet.noneOf(TwoFactorMethod.class);
            copy.addAll(this.twoFactorMethods);
            copy.add(value);
            this.twoFactorMethods = copy;
            return this;
        }

        public Builder removeTwoFactorMethod(TwoFactorMethod value) {
            EnumSet<TwoFactorMethod> copy = EnumSet.noneOf(TwoFactorMethod.class);
            copy.addAll(this.twoFactorMethods);
            copy.remove(value);
            this.twoFactorMethods = copy;
            return this;
        }

        public Builder createdAt(Instant value) {
            this.createdAt = value;
            return this;
        }

        public Builder updatedAt(Instant value) {
            this.updatedAt = value;
            return this;
        }

        public @NotNull Account build() {
            return new Account(this);
        }
    }

    // ------------------------------------------------------------------ identity

    /**
     * Равенство по {@link #uuid()}.
     *
     * <p>Не по всем полям: два чтения одного аккаунта, разделённые обновлением
     * {@code last_seen_at}, описывают один и тот же аккаунт. И не по {@link #id()}:
     * у несохранённого аккаунта он нулевой, и все такие объекты оказались бы равны.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Account other && uuid.equals(other.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    /** Хэш пароля заменён заглушкой: {@code toString()} слишком легко попадает в лог. */
    @Override
    public String toString() {
        return "Account{id=" + id
                + ", uuid=" + uuid
                + ", username='" + username + '\''
                + ", status=" + status
                + ", premium=" + premium
                + ", twoFactor=" + twoFactorMethods
                + ", passwordHash=<redacted>}";
    }
}
