package net.kofnetwork.auth.core.mail;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.exception.KoFAuthException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

/**
 * Отправка писем по SMTP.
 *
 * <p>Работает на отдельном пуле {@code kofauth-mail}: SMTP-сервер может отвечать
 * секундами, и общий пул с базой означал бы, что зависший почтовый сервер
 * останавливает входы на всей сети.
 *
 * <p>Таймауты выставляются всегда. Без них зависшее соединение держит поток из
 * пула до бесконечности, и отправка писем встаёт целиком после пары таких зависаний.
 */
public final class SmtpMailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmtpMailSender.class);

    private final ConfigurationService config;

    public SmtpMailSender(@NotNull ConfigurationService config) {
        this.config = config;
    }

    /** Настроена ли отправка почты. */
    public boolean isConfigured() {
        return config.getBoolean(ConfigFile.MAIL, "enabled", false)
                && !config.getString(ConfigFile.MAIL, "smtp.host", "").isBlank();
    }

    /**
     * Отправляет письмо. Блокирующая операция — вызывать только на почтовом пуле.
     *
     * @throws KoFAuthException при отказе SMTP
     */
    public void send(@NotNull String to, @NotNull String subject, @NotNull String htmlBody) {
        if (!isConfigured()) {
            throw new KoFAuthException("Отправка почты не настроена (mail.yml, enabled: false)");
        }

        String host = config.getString(ConfigFile.MAIL, "smtp.host", "");
        int port = config.getInt(ConfigFile.MAIL, "smtp.port", 587);
        String username = config.getString(ConfigFile.MAIL, "smtp.username", "");
        String password = config.getString(ConfigFile.MAIL, "smtp.password", "");
        boolean starttls = config.getBoolean(ConfigFile.MAIL, "smtp.starttls", true);
        boolean ssl = config.getBoolean(ConfigFile.MAIL, "smtp.ssl", false);

        Duration connectTimeout = config.getDuration(ConfigFile.MAIL,
                "smtp.connection-timeout", Duration.ofSeconds(10));
        Duration readTimeout = config.getDuration(ConfigFile.MAIL,
                "smtp.read-timeout", Duration.ofSeconds(10));

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls && !ssl));
        props.put("mail.smtp.connectiontimeout", String.valueOf(connectTimeout.toMillis()));
        props.put("mail.smtp.timeout", String.valueOf(readTimeout.toMillis()));
        props.put("mail.smtp.writetimeout", String.valueOf(readTimeout.toMillis()));
        if (ssl) {
            props.put("mail.smtp.ssl.enable", "true");
        }

        Session session = username.isBlank()
                ? Session.getInstance(props)
                : Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                });

        try {
            MimeMessage message = new MimeMessage(session);
            String fromAddress = config.getString(ConfigFile.MAIL, "from.address", "noreply@localhost");
            String fromName = config.getString(ConfigFile.MAIL, "from.name", "KoF Network");
            message.setFrom(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));

            String replyTo = config.getString(ConfigFile.MAIL, "reply-to", "");
            if (!replyTo.isBlank()) {
                message.setReplyTo(InternetAddress.parse(replyTo));
            }

            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setContent(htmlBody, "text/html; charset=UTF-8");

            Transport.send(message);
            LOGGER.debug("Письмо отправлено на {}", mask(to));
        } catch (Exception e) {
            throw new KoFAuthException("Не удалось отправить письмо: " + e.getMessage(), e);
        }
    }

    /**
     * Маскирует адрес: {@code s***@example.com}.
     *
     * <p>Используется и в логах, и в событиях привязки. Полный адрес не должен
     * попадать ни в общий лог сервера, ни в канал Pub/Sub — это персональные данные,
     * а событие о привязке почты видят все узлы сети.
     */
    public static @NotNull String mask(@NotNull String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at < 0 ? "" : email.substring(at));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
