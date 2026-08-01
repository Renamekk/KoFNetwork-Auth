package net.kofnetwork.auth.core.security;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Политика сложности паролей.
 *
 * <p>Возвращает <b>список</b> всех невыполненных требований, а не первое попавшееся.
 * Сообщать о требованиях по одному за попытку — верный способ заставить человека
 * подбирать пароль пятью заходами; он в итоге напишет {@code Password1!} и запишет
 * его на бумажке.
 *
 * <p><b>Чего здесь намеренно нет — обязательной смены пароля по расписанию.</b>
 * Периодическая ротация приводит к паролям вида {@code Лето2026!}, {@code Осень2026!},
 * то есть снижает стойкость. NIST SP 800-63B рекомендует не требовать смены без
 * признаков компрометации.
 */
public final class PasswordPolicy {

    // Коды проблем. Наружу уходят именно они, а не готовый текст: сообщение
    // выбирается по коду из файла локализации.
    public static final String TOO_SHORT = "PASSWORD_TOO_SHORT";
    public static final String TOO_LONG = "PASSWORD_TOO_LONG";
    public static final String NO_UPPERCASE = "PASSWORD_NO_UPPERCASE";
    public static final String NO_LOWERCASE = "PASSWORD_NO_LOWERCASE";
    public static final String NO_DIGIT = "PASSWORD_NO_DIGIT";
    public static final String NO_SPECIAL = "PASSWORD_NO_SPECIAL";
    public static final String CONTAINS_USERNAME = "PASSWORD_CONTAINS_USERNAME";
    public static final String TOO_COMMON = "PASSWORD_TOO_COMMON";
    public static final String REPEATED_CHARACTERS = "PASSWORD_REPEATED_CHARACTERS";
    public static final String SEQUENTIAL_CHARACTERS = "PASSWORD_SEQUENTIAL_CHARACTERS";

    /**
     * Пароли, которые перебираются в первую очередь. Список короткий намеренно:
     * полноценная проверка по утечкам — задача внешней службы (Have I Been Pwned),
     * а держать в памяти игрового сервера словарь на миллион строк смысла нет.
     */
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "123456", "12345678", "123456789", "qwerty", "qwerty123",
            "abc123", "111111", "1234567890", "letmein", "welcome", "admin",
            "minecraft", "monkey", "dragon", "master", "login", "passw0rd",
            "password1", "iloveyou", "sunshine", "princess", "football",
            "йцукен", "пароль", "привет", "123123", "qazwsx", "zxcvbn");

    private final int minLength;
    private final int maxLength;
    private final boolean requireUppercase;
    private final boolean requireLowercase;
    private final boolean requireDigit;
    private final boolean requireSpecial;
    private final boolean forbidUsername;
    private final boolean forbidCommon;
    private final boolean forbidSequences;

    private PasswordPolicy(Builder b) {
        this.minLength = b.minLength;
        this.maxLength = b.maxLength;
        this.requireUppercase = b.requireUppercase;
        this.requireLowercase = b.requireLowercase;
        this.requireDigit = b.requireDigit;
        this.requireSpecial = b.requireSpecial;
        this.forbidUsername = b.forbidUsername;
        this.forbidCommon = b.forbidCommon;
        this.forbidSequences = b.forbidSequences;
    }

    /**
     * Проверяет пароль.
     *
     * @param username ник владельца — пароль не должен его содержать
     * @return коды всех невыполненных требований; пустой список означает, что пароль принят
     */
    public @NotNull List<String> validate(@NotNull String password, @NotNull String username) {
        List<String> issues = new ArrayList<>();

        if (password.length() < minLength) {
            issues.add(TOO_SHORT);
        }
        // Ограничение считается в байтах UTF-8: BCrypt учитывает первые 72 байта,
        // а кириллица занимает два байта на символ, поэтому 40 русских букв — уже 80 байт.
        int byteLength = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() > maxLength || byteLength > PasswordHasher.MAX_PASSWORD_BYTES) {
            issues.add(TOO_LONG);
        }

        if (requireUppercase && password.chars().noneMatch(Character::isUpperCase)) {
            issues.add(NO_UPPERCASE);
        }
        if (requireLowercase && password.chars().noneMatch(Character::isLowerCase)) {
            issues.add(NO_LOWERCASE);
        }
        if (requireDigit && password.chars().noneMatch(Character::isDigit)) {
            issues.add(NO_DIGIT);
        }
        if (requireSpecial && password.chars().noneMatch(PasswordPolicy::isSpecial)) {
            issues.add(NO_SPECIAL);
        }

        String lower = password.toLowerCase(Locale.ROOT);

        if (forbidUsername && !username.isBlank()) {
            String lowerUser = username.toLowerCase(Locale.ROOT);
            if (lower.contains(lowerUser) || lowerUser.contains(lower)) {
                issues.add(CONTAINS_USERNAME);
            }
        }
        if (forbidCommon && COMMON_PASSWORDS.contains(lower)) {
            issues.add(TOO_COMMON);
        }
        if (forbidSequences) {
            if (hasRepeatedRun(password, 4)) {
                issues.add(REPEATED_CHARACTERS);
            }
            if (hasSequentialRun(lower, 5)) {
                issues.add(SEQUENTIAL_CHARACTERS);
            }
        }

        return issues;
    }

    /** Принят ли пароль. */
    public boolean isAcceptable(@NotNull String password, @NotNull String username) {
        return validate(password, username).isEmpty();
    }

    private static boolean isSpecial(int codePoint) {
        return !Character.isLetterOrDigit(codePoint) && !Character.isWhitespace(codePoint);
    }

    /** Есть ли подряд {@code run} одинаковых символов: {@code aaaa}, {@code 1111}. */
    private static boolean hasRepeatedRun(String value, int run) {
        int streak = 1;
        for (int i = 1; i < value.length(); i++) {
            streak = value.charAt(i) == value.charAt(i - 1) ? streak + 1 : 1;
            if (streak >= run) {
                return true;
            }
        }
        return false;
    }

    /**
     * Есть ли подряд {@code run} соседних по коду символов: {@code 12345}, {@code abcde},
     * а также убывающие последовательности {@code 54321}.
     */
    private static boolean hasSequentialRun(String value, int run) {
        if (value.length() < run) {
            return false;
        }
        int ascending = 1;
        int descending = 1;
        for (int i = 1; i < value.length(); i++) {
            int delta = value.charAt(i) - value.charAt(i - 1);
            ascending = delta == 1 ? ascending + 1 : 1;
            descending = delta == -1 ? descending + 1 : 1;
            if (ascending >= run || descending >= run) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ builder

    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Разумные значения по умолчанию: 8 символов, буквы обоих регистров и цифра.
     *
     * <p>Спецсимвол не требуется: его обязательность даёт небольшой прирост стойкости,
     * но заметно увеличивает долю забытых паролей, а каждое восстановление — это ещё
     * один путь к захвату аккаунта.
     */
    public static @NotNull PasswordPolicy defaults() {
        return builder().build();
    }

    /** Строитель {@link PasswordPolicy}. */
    public static final class Builder {

        private int minLength = 8;
        private int maxLength = 64;
        private boolean requireUppercase = true;
        private boolean requireLowercase = true;
        private boolean requireDigit = true;
        private boolean requireSpecial;
        private boolean forbidUsername = true;
        private boolean forbidCommon = true;
        private boolean forbidSequences = true;

        private Builder() {
        }

        public Builder minLength(int value) {
            this.minLength = value;
            return this;
        }

        public Builder maxLength(int value) {
            this.maxLength = value;
            return this;
        }

        public Builder requireUppercase(boolean value) {
            this.requireUppercase = value;
            return this;
        }

        public Builder requireLowercase(boolean value) {
            this.requireLowercase = value;
            return this;
        }

        public Builder requireDigit(boolean value) {
            this.requireDigit = value;
            return this;
        }

        public Builder requireSpecial(boolean value) {
            this.requireSpecial = value;
            return this;
        }

        public Builder forbidUsername(boolean value) {
            this.forbidUsername = value;
            return this;
        }

        public Builder forbidCommon(boolean value) {
            this.forbidCommon = value;
            return this;
        }

        public Builder forbidSequences(boolean value) {
            this.forbidSequences = value;
            return this;
        }

        public @NotNull PasswordPolicy build() {
            return new PasswordPolicy(this);
        }
    }
}
