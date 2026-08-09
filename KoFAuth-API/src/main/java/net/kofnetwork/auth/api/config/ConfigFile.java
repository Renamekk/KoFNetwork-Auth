package net.kofnetwork.auth.api.config;

import org.jetbrains.annotations.NotNull;

/**
 * Файлы конфигурации KoFAuth.
 *
 * <p>Настройки разделены по девяти файлам, а не собраны в один, по двум причинам.
 * Во-первых, у них разный жизненный цикл: {@link #DATABASE} правят один раз при
 * развёртывании, {@link #SECURITY} — регулярно. Во-вторых, у них разная секретность:
 * {@link #TELEGRAM} и {@link #DISCORD} содержат токены ботов, и их можно смонтировать
 * в контейнер отдельно, с более строгими правами, не выдавая наружу весь конфиг.
 */
public enum ConfigFile {

    /** Общие настройки: язык, поведение при входе, личный кабинет. */
    CONFIG("config.yml"),

    /**
     * Тексты, которые видит игрок: чат, кики, надписи на экране.
     *
     * <p>Вынесены в отдельный файл, потому что правят их чаще всего и правят
     * другие люди. Администратор, меняющий формулировку, не должен ходить в файл,
     * где рядом лежат сроки жизни сессий.
     */
    MESSAGES("messages.yml"),

    /** MySQL, HikariCP, Redis. */
    DATABASE("database.yml"),

    /** Токен и поведение Telegram-бота. */
    TELEGRAM("telegram.yml"),

    /** Токен, OAuth2 и поведение Discord-бота. */
    DISCORD("discord.yml"),

    /** SMTP и шаблоны писем. */
    MAIL("mail.yml"),

    /** Типы CAPTCHA, сложность, сроки. */
    CAPTCHA("captcha.yml"),

    /** BCrypt, AES, JWT, ограничения скорости, AntiBot, AntiVPN. */
    SECURITY("security.yml"),

    /** Настройки прокси: Limbo, лобби, поведение до входа. */
    VELOCITY("velocity.yml"),

    /** Настройки Paper: режим модуля, мир Limbo, GUI. */
    PAPER("paper.yml");

    private final String fileName;

    ConfigFile(String fileName) {
        this.fileName = fileName;
    }

    public @NotNull String fileName() {
        return fileName;
    }
}
