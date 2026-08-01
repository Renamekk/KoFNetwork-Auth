package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.BindingChangedEvent;
import net.kofnetwork.auth.api.model.EmailBinding;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.TokenType;
import net.kofnetwork.auth.api.repository.EmailRepository;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.api.service.AuditService;
import net.kofnetwork.auth.api.service.EmailService;
import net.kofnetwork.auth.api.service.SecurityService;
import net.kofnetwork.auth.api.service.TokenService;
import net.kofnetwork.auth.core.concurrent.AsyncExecutors;
import net.kofnetwork.auth.core.mail.MailTemplateEngine;
import net.kofnetwork.auth.core.mail.SmtpMailSender;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Реализация {@link EmailService}. */
public final class EmailServiceImpl implements EmailService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailServiceImpl.class);

    /**
     * Проверка формата адреса.
     *
     * <p>Намеренно упрощённая. Полная грамматика RFC 5322 допускает адреса, которые
     * не примет ни один реальный провайдер, а строгая регулярка отвергает валидные.
     * Настоящая проверка адреса одна — отправить на него письмо и получить
     * подтверждение, что и делается дальше.
     */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    public static final String INVALID_FORMAT = "EMAIL_INVALID_FORMAT";
    public static final String BLOCKED_DOMAIN = "EMAIL_BLOCKED_DOMAIN";
    public static final String TOO_LONG = "EMAIL_TOO_LONG";

    private final EmailRepository emails;
    private final TokenService tokens;
    private final SecurityService security;
    private final AuditService audit;
    private final SmtpMailSender sender;
    private final MailTemplateEngine templates;
    private final ConfigurationService config;
    private final AsyncExecutors executors;
    private final EventBus events;

    public EmailServiceImpl(@NotNull EmailRepository emails,
                            @NotNull TokenService tokens,
                            @NotNull SecurityService security,
                            @NotNull AuditService audit,
                            @NotNull SmtpMailSender sender,
                            @NotNull MailTemplateEngine templates,
                            @NotNull ConfigurationService config,
                            @NotNull AsyncExecutors executors,
                            @NotNull EventBus events) {
        this.emails = emails;
        this.tokens = tokens;
        this.security = security;
        this.audit = audit;
        this.sender = sender;
        this.templates = templates;
        this.config = config;
        this.executors = executors;
        this.events = events;
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> linkEmail(long accountId,
                                                                       @NotNull String email,
                                                                       @NotNull AuthContext context) {
        Optional<String> issue = validateEmail(email);
        if (issue.isPresent()) {
            return completed(OperationResult.fail(issue.get(), "Адрес не принят"));
        }
        if (!isConfigured()) {
            return completed(OperationResult.fail("MAIL_DISABLED", "Отправка почты не настроена"));
        }

        String normalized = EmailBinding.normalize(email);

        return emails.countAccountsByEmail(normalized).thenCompose(linked -> {
            int max = config.getInt(ConfigFile.MAIL, "max-accounts-per-address", 3);
            if (max > 0 && linked >= max) {
                return completed(OperationResult.<Void>fail("EMAIL_LIMIT_REACHED",
                        "К этому адресу уже привязано слишком много аккаунтов"));
            }
            return emails.findByAccount(accountId).thenCompose(existing -> {
                boolean primary = existing.isEmpty();
                return emails.insert(EmailBinding.pending(accountId, email, primary))
                        .thenCompose(saved -> sendVerification(accountId, saved, context));
            });
        });
    }

    private CompletableFuture<OperationResult<Void>> sendVerification(long accountId,
                                                                      EmailBinding binding,
                                                                      AuthContext context) {
        return tokens.issue(accountId, TokenType.EMAIL_VERIFY, context.ip(), null)
                .thenCompose(issued -> sendTemplated(binding.email(), "verify-email",
                        Map.of("code", issued.value(),
                                "email", binding.email(),
                                "expires", "24 часа")))
                .thenCompose(result -> events.publish(BindingChangedEvent.of(accountId,
                                BindingChangedEvent.BindingKind.EMAIL,
                                BindingChangedEvent.Action.LINKED,
                                SmtpMailSender.mask(binding.email()), context))
                        .thenApply(ignored -> result));
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> verifyEmail(@NotNull String token,
                                                                          @NotNull AuthContext context) {
        return tokens.consume(token, TokenType.EMAIL_VERIFY, context.ip()).thenCompose(result -> {
            if (result.isFailure()) {
                return completed(OperationResult.<Void>fail("TOKEN_INVALID",
                        "Код недействителен или истёк"));
            }
            Long accountId = result.value().accountId();
            if (accountId == null) {
                return completed(OperationResult.<Void>fail("TOKEN_INVALID",
                        "Код не связан с аккаунтом"));
            }
            return emails.findPrimary(accountId).thenCompose(binding -> {
                if (binding.isEmpty()) {
                    return completed(OperationResult.<Void>fail("EMAIL_NOT_FOUND",
                            "Привязка не найдена"));
                }
                return emails.markVerified(binding.get().id(), Instant.now())
                        .thenCompose(ignored -> events.publish(BindingChangedEvent.of(accountId,
                                BindingChangedEvent.BindingKind.EMAIL,
                                BindingChangedEvent.Action.VERIFIED,
                                SmtpMailSender.mask(binding.get().email()), context)))
                        .thenCompose(ignored -> audit.log(accountId,
                                SecurityEventType.EMAIL_VERIFIED, context, "Почта подтверждена"))
                        .thenApply(ignored -> OperationResult.<Void>ok());
            });
        });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> resendVerification(
            long accountId, @NotNull AuthContext context) {
        // Без ограничения скорости форма повторной отправки превращается
        // в бесплатный генератор писем на любой адрес.
        return security.checkAndConsume("email-resend.per-account", String.valueOf(accountId))
                .thenCompose(verdict -> {
                    if (!verdict.allowed()) {
                        return completed(OperationResult.<Void>fail("RATE_LIMITED",
                                "Слишком часто. Попробуйте позже."));
                    }
                    return emails.findPrimary(accountId).thenCompose(binding -> {
                        if (binding.isEmpty()) {
                            return completed(OperationResult.<Void>fail("EMAIL_NOT_FOUND",
                                    "Почта не привязана"));
                        }
                        if (binding.get().verified()) {
                            return completed(OperationResult.<Void>fail("EMAIL_ALREADY_VERIFIED",
                                    "Почта уже подтверждена"));
                        }
                        return sendVerification(accountId, binding.get(), context);
                    });
                });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> unlinkEmail(long accountId,
                                                                          @NotNull AuthContext context) {
        return emails.findPrimary(accountId).thenCompose(binding -> {
            if (binding.isEmpty()) {
                return completed(OperationResult.<Void>fail("EMAIL_NOT_FOUND", "Почта не привязана"));
            }
            return emails.delete(binding.get().id())
                    .thenCompose(ignored -> events.publish(BindingChangedEvent.of(accountId,
                            BindingChangedEvent.BindingKind.EMAIL,
                            BindingChangedEvent.Action.UNLINKED,
                            SmtpMailSender.mask(binding.get().email()), context)))
                    .thenCompose(ignored -> audit.log(accountId, SecurityEventType.EMAIL_UNLINKED,
                            context, "Почта отвязана"))
                    .thenApply(ignored -> OperationResult.<Void>ok());
        });
    }

    @Override
    public @NotNull CompletableFuture<Optional<EmailBinding>> findPrimary(long accountId) {
        return emails.findPrimary(accountId);
    }

    @Override
    public @NotNull CompletableFuture<List<EmailBinding>> findAll(long accountId) {
        return emails.findByAccount(accountId);
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> updateNotificationSettings(
            long accountId, boolean notifyLogin, boolean notifySecurity, boolean notifyNewsletter) {
        return emails.findPrimary(accountId).thenCompose(binding -> {
            if (binding.isEmpty()) {
                return completed(OperationResult.<Void>fail("EMAIL_NOT_FOUND", "Почта не привязана"));
            }
            return emails.updateNotificationSettings(binding.get().id(), notifyLogin,
                            notifySecurity, notifyNewsletter)
                    .thenApply(ignored -> OperationResult.<Void>ok());
        });
    }

    @Override
    public @NotNull CompletableFuture<OperationResult<Void>> sendTemplated(
            @NotNull String to, @NotNull String template, @NotNull Map<String, String> variables) {
        if (!isConfigured()) {
            return completed(OperationResult.fail("MAIL_DISABLED", "Отправка почты не настроена"));
        }
        // Отправка идёт на почтовом пуле: SMTP блокирует поток на секунды.
        return executors.supplyMail(() -> {
            try {
                MailTemplateEngine.Rendered rendered = templates.render(template, variables);
                sender.send(to, rendered.subject(), rendered.body());
                return OperationResult.<Void>ok();
            } catch (RuntimeException e) {
                LOGGER.error("Не удалось отправить письмо '{}' на {}", template,
                        SmtpMailSender.mask(to), e);
                return OperationResult.<Void>fail("MAIL_SEND_FAILED", e.getMessage());
            }
        });
    }

    @Override
    public @NotNull Optional<String> validateEmail(@NotNull String email) {
        String trimmed = email.trim();
        if (trimmed.length() > 254) {
            return Optional.of(TOO_LONG);
        }
        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            return Optional.of(INVALID_FORMAT);
        }
        String domain = trimmed.substring(trimmed.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        if (config.getStringList(ConfigFile.MAIL, "blocked-domains").stream()
                .anyMatch(blocked -> blocked.equalsIgnoreCase(domain))) {
            // Восстановить аккаунт через ящик, который живёт десять минут,
            // всё равно не выйдет — лучше не давать привязать его вовсе.
            return Optional.of(BLOCKED_DOMAIN);
        }
        return Optional.empty();
    }

    @Override
    public boolean isConfigured() {
        return sender.isConfigured();
    }

    private static <T> CompletableFuture<OperationResult<T>> completed(OperationResult<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
