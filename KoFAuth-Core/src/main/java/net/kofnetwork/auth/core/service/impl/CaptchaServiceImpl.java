package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.CaptchaStatus;
import net.kofnetwork.auth.api.model.CaptchaType;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.repository.AccountRepository;
import net.kofnetwork.auth.api.repository.CaptchaRepository;
import net.kofnetwork.auth.api.service.AuditService;
import net.kofnetwork.auth.api.service.CaptchaService;
import net.kofnetwork.auth.api.service.SecurityService;
import net.kofnetwork.auth.core.security.TokenGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link CaptchaService}.
 *
 * <p>Сервис владеет только логикой «выдать задачу — проверить ответ». Рисовать он
 * не умеет: за это отвечают {@link CaptchaRenderer}, регистрируемые платформенными
 * модулями. Благодаря этому новый вид CAPTCHA добавляется регистрацией рендерера,
 * без изменений здесь.
 */
public final class CaptchaServiceImpl implements CaptchaService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptchaServiceImpl.class);

    private final CaptchaRepository captcha;
    private final AccountRepository accounts;
    private final SecurityService security;
    private final AuditService audit;
    private final ConfigurationService config;

    private final Map<CaptchaType, CaptchaRenderer> renderers = new EnumMap<>(CaptchaType.class);

    public CaptchaServiceImpl(@NotNull CaptchaRepository captcha,
                              @NotNull AccountRepository accounts,
                              @NotNull SecurityService security,
                              @NotNull AuditService audit,
                              @NotNull ConfigurationService config) {
        this.captcha = captcha;
        this.accounts = accounts;
        this.security = security;
        this.audit = audit;
        this.config = config;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> isRequired(@Nullable Long accountId,
                                                           @NotNull AuthContext context) {
        if (!config.getBoolean(ConfigFile.CAPTCHA, "enabled", true)) {
            return CompletableFuture.completedFuture(false);
        }
        if (accountId == null) {
            // Незарегистрированный игрок: требование задаётся настройкой регистрации.
            return CompletableFuture.completedFuture(
                    config.getBoolean(ConfigFile.CAPTCHA, "require-on.every-register", true));
        }
        return accounts.findById(accountId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            boolean firstJoin = config.getBoolean(ConfigFile.CAPTCHA, "require-on.first-join", true);
            if (firstJoin && !found.get().captchaPassed()) {
                return CompletableFuture.completedFuture(true);
            }
            if (!config.getBoolean(ConfigFile.CAPTCHA, "require-on.suspicious", true)) {
                return CompletableFuture.completedFuture(false);
            }
            return security.requiresElevatedVerification(accountId, context);
        });
    }

    @Override
    public @NotNull CompletableFuture<CaptchaChallenge> issue(@Nullable Long accountId,
                                                               @NotNull UUID playerUuid,
                                                               @Nullable CaptchaType type,
                                                               @NotNull AuthContext context) {
        return resolveType(accountId, type, context).thenCompose(resolved -> {
            CaptchaRenderer renderer = renderers.get(resolved);
            if (renderer == null) {
                // Тип настроен, но платформа его не умеет — берём любой доступный,
                // иначе игрок застрянет с невидимой задачей.
                resolved = renderers.keySet().stream().findFirst().orElse(CaptchaType.TEXT_INPUT);
                LOGGER.warn("Нет рендерера для типа {}, используется {}", type, resolved);
            }

            String answer = generateAnswer(resolved);
            Duration ttl = config.getDuration(ConfigFile.CAPTCHA, "ttl", Duration.ofMinutes(2));
            int maxAttempts = config.getInt(ConfigFile.CAPTCHA, "max-attempts", 3);

            CaptchaChallenge challenge = CaptchaChallenge.issue(accountId, playerUuid, resolved,
                    // В базу уходит только хэш: дамп не должен давать готовых ответов
                    // на активные задачи.
                    TokenGenerator.hash(answer), context.ip(), maxAttempts, ttl);

            return captcha.insert(challenge)
                    .thenApply(saved -> {
                        pendingAnswers.put(saved.challengeId(), answer);
                        return saved;
                    });
        });
    }

    /**
     * Открытые ответы на активные задачи.
     *
     * <p>Живут в памяти процесса, а не в базе: задачу выдаёт и проверяет один и тот
     * же Limbo-сервер, а хранить открытый ответ дольше, чем длится задача, незачем.
     * При перезапуске Limbo незавершённые задачи теряются — игрок получит новую.
     */
    private final Map<String, String> pendingAnswers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Выбирает тип с учётом повышения сложности. */
    private CompletableFuture<CaptchaType> resolveType(@Nullable Long accountId,
                                                       @Nullable CaptchaType requested,
                                                       AuthContext context) {
        if (requested != null) {
            return CompletableFuture.completedFuture(requested);
        }
        CaptchaType base = parseType(config.getString(ConfigFile.CAPTCHA, "type", "GUI_GRID"),
                CaptchaType.GUI_GRID);

        if (!config.getBoolean(ConfigFile.CAPTCHA, "escalate-on-failure", true) || accountId == null) {
            return CompletableFuture.completedFuture(base);
        }
        CaptchaType escalated = parseType(
                config.getString(ConfigFile.CAPTCHA, "escalated-type", "MAP_IMAGE"),
                CaptchaType.MAP_IMAGE);

        return security.requiresElevatedVerification(accountId, context)
                .thenApply(elevated -> elevated ? escalated : base);
    }

    private static CaptchaType parseType(String raw, CaptchaType fallback) {
        try {
            return CaptchaType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Генерирует правильный ответ для типа задачи. */
    private String generateAnswer(CaptchaType type) {
        return switch (type) {
            case TEXT_INPUT, MAP_IMAGE -> TokenGenerator.humanReadableCode(
                    Math.max(6, config.getInt(ConfigFile.CAPTCHA, "text.length", 6)));
            // Для GUI ответом служит позиция ячейки: рендерер получает её из
            // хэша и раскладывает отвлекающие варианты вокруг.
            case GUI_GRID, BLOCK_SELECT, BUTTON_CLICK -> String.valueOf(
                    java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 1 + gridCells()));
        };
    }

    private int gridCells() {
        int size = config.getInt(ConfigFile.CAPTCHA, "gui.grid-size", 4);
        int rows = config.getInt(ConfigFile.CAPTCHA, "gui.rows", 3);
        return Math.max(4, size * rows);
    }

    @Override
    public @NotNull CompletableFuture<CaptchaVerdict> verify(@NotNull String challengeId,
                                                              @NotNull String answer) {
        return captcha.findByChallengeId(challengeId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(CaptchaVerdict.notFound());
            }
            CaptchaChallenge challenge = found.get();
            Instant now = Instant.now();

            if (!challenge.isAnswerable(now)) {
                return CompletableFuture.completedFuture(CaptchaVerdict.notFound());
            }

            // Сравнение хэшей в постоянном времени: ответ короткий, и посимвольное
            // сравнение с ранним выходом теоретически позволяет его подобрать.
            boolean correct = TokenGenerator.constantTimeEquals(
                    challenge.expectedAnswerHash(), TokenGenerator.hash(answer.trim()));

            if (correct) {
                pendingAnswers.remove(challengeId);
                CaptchaChallenge passed = challenge.pass(now);
                return captcha.update(passed)
                        .thenCompose(saved -> markAccountPassed(challenge)
                                .thenApply(ignored -> CaptchaVerdict.pass(saved)));
            }

            CaptchaChallenge failed = challenge.failAttempt(now);
            return captcha.update(failed).thenCompose(saved -> {
                if (saved.status() == CaptchaStatus.FAILED) {
                    pendingAnswers.remove(challengeId);
                    return audit.log(challenge.accountId(), SecurityEventType.CAPTCHA_FAILED,
                                    AuthContext.minecraft(challenge.ip(), null, null, null),
                                    "Исчерпаны попытки прохождения CAPTCHA")
                            .thenApply(ignored -> CaptchaVerdict.fail(saved));
                }
                return CompletableFuture.completedFuture(CaptchaVerdict.fail(saved));
            });
        });
    }

    private CompletableFuture<Void> markAccountPassed(CaptchaChallenge challenge) {
        if (challenge.accountId() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return accounts.markCaptchaPassed(challenge.accountId(), true)
                .thenCompose(ignored -> audit.log(challenge.accountId(),
                        SecurityEventType.CAPTCHA_PASSED,
                        AuthContext.minecraft(challenge.ip(), null, null, null),
                        "CAPTCHA пройдена"));
    }

    @Override
    public @NotNull CompletableFuture<Optional<CaptchaChallenge>> findPending(@NotNull UUID playerUuid) {
        return captcha.findPendingByPlayer(playerUuid, Instant.now());
    }

    @Override
    public @NotNull CompletableFuture<Void> cancel(@NotNull UUID playerUuid) {
        return captcha.findPendingByPlayer(playerUuid, Instant.now()).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            pendingAnswers.remove(found.get().challengeId());
            return captcha.update(found.get().expire(Instant.now())).thenApply(ignored -> null);
        });
    }

    @Override
    public void registerRenderer(@NotNull CaptchaRenderer renderer) {
        for (CaptchaType type : renderer.supportedTypes()) {
            renderers.put(type, renderer);
        }
        LOGGER.info("Зарегистрирован рендерер CAPTCHA для типов: {}", renderer.supportedTypes());
    }

    /**
     * Готовое к показу содержимое задачи.
     *
     * <p>Ответ передаётся рендереру здесь, внутри сервиса, и наружу не отдаётся:
     * платформенный код получает только раскладку, но не знает, какой вариант верный.
     *
     * @return {@code null}, если рендерера для типа нет либо задача уже завершена
     */
    public @Nullable RenderedCaptcha render(@NotNull CaptchaChallenge challenge) {
        CaptchaRenderer renderer = renderers.get(challenge.type());
        if (renderer == null) {
            return null;
        }
        String answer = pendingAnswers.get(challenge.challengeId());
        if (answer == null) {
            // Задача выдана другим процессом (например, до перезапуска Limbo):
            // открытый ответ утерян, и разложить её нечем. Вызывающий выдаст новую.
            LOGGER.debug("Нет открытого ответа для задачи {}, требуется перевыдача",
                    challenge.challengeId());
            return null;
        }
        return renderer.render(challenge, answer);
    }

    /** Типы, для которых есть рендерер. */
    public @NotNull List<CaptchaType> availableTypes() {
        return List.copyOf(renderers.keySet());
    }
}
