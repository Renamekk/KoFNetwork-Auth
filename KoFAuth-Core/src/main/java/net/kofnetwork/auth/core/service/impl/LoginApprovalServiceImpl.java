package net.kofnetwork.auth.core.service.impl;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.event.events.LoginApprovalDecidedEvent;
import net.kofnetwork.auth.api.event.events.SuspiciousActivityEvent;
import net.kofnetwork.auth.api.model.Account;
import net.kofnetwork.auth.api.model.ApprovalStatus;
import net.kofnetwork.auth.api.model.BotMessage;
import net.kofnetwork.auth.api.model.BotPlatform;
import net.kofnetwork.auth.api.model.EventSource;
import net.kofnetwork.auth.api.model.LoginApproval;
import net.kofnetwork.auth.api.model.SecurityEventType;
import net.kofnetwork.auth.api.model.Session;
import net.kofnetwork.auth.api.repository.BotOutboxRepository;
import net.kofnetwork.auth.api.repository.LoginApprovalRepository;
import net.kofnetwork.auth.api.result.OperationResult;
import net.kofnetwork.auth.api.service.AuditService;
import net.kofnetwork.auth.api.service.LinkService;
import net.kofnetwork.auth.api.service.LoginApprovalService;
import net.kofnetwork.auth.core.security.TokenGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация {@link LoginApprovalService}.
 *
 * <p><b>Что здесь заменило прежний код подтверждения.</b> Раньше вторым фактором служил
 * токен {@code LOGIN_APPROVAL}: предъявительский, выпускаемый по команде бота и никак
 * не связанный с попыткой входа. Он позволял получить сессию вообще без пароля,
 * принимался от кого угодно и, будучи погашен, создавал отдельную сессию мессенджера
 * вместо завершения игрового входа. Здесь каждое из этих свойств заменено обратным:
 * подтверждение выпускает только сервер и только после проверки пароля, оно связано с
 * конкретной попыткой и получателем, а одобрение доводит до конца именно ту попытку.
 */
public final class LoginApprovalServiceImpl implements LoginApprovalService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginApprovalServiceImpl.class);

    /**
     * Длина идентификатора запроса.
     *
     * <p>Шестнадцать байт дают около 22 знаков в base64url — вместе с префиксом
     * кнопки это укладывается в 64 байта {@code callback_data} Telegram с большим
     * запасом. Меньше брать нельзя: {@code TokenGenerator} отвергает значения короче
     * 128 бит, и правильно делает — идентификатор виден в кнопке, и подбирать его
     * не должно быть осмысленно, даже если сам по себе он ничего не открывает.
     */
    private static final int PUBLIC_ID_BYTES = 16;

    /**
     * Завершение исходной попытки входа.
     *
     * <p>Отдельный интерфейс, а не ссылка на {@code AuthenticationService}, чтобы разорвать
     * цикл: аутентификация выпускает подтверждения, подтверждения завершают аутентификацию.
     * Реализацию подставляет композиционный корень после того, как оба сервиса построены.
     */
    @FunctionalInterface
    public interface LoginCompleter {

        /** @return созданная сессия либо пустое значение, если завершить вход не удалось */
        @NotNull CompletableFuture<Optional<Session>> complete(@NotNull LoginApproval approval);
    }

    private final LoginApprovalRepository approvals;
    private final BotOutboxRepository outbox;
    private final LinkService links;
    private final AuditService audit;
    private final EventBus events;
    private final ConfigurationService config;

    private volatile LoginCompleter completer = approval ->
            CompletableFuture.completedFuture(Optional.empty());

    public LoginApprovalServiceImpl(@NotNull LoginApprovalRepository approvals,
                                    @NotNull BotOutboxRepository outbox,
                                    @NotNull LinkService links,
                                    @NotNull AuditService audit,
                                    @NotNull EventBus events,
                                    @NotNull ConfigurationService config) {
        this.approvals = approvals;
        this.outbox = outbox;
        this.links = links;
        this.audit = audit;
        this.events = events;
        this.config = config;
    }

    /** Подставляет завершение входа. Вызывается композиционным корнем один раз. */
    public void attachCompleter(@NotNull LoginCompleter loginCompleter) {
        this.completer = loginCompleter;
    }

    private Duration lifetime() {
        return config.getDuration(ConfigFile.SECURITY, "two-factor.approval-lifetime",
                LoginApproval.DEFAULT_LIFETIME);
    }

    // ================================================================ выпуск

    @Override
    public @NotNull CompletableFuture<OperationResult<LoginApproval>> request(
            @NotNull Account account,
            @Nullable UUID playerUuid,
            @NotNull String attemptId,
            @NotNull BotPlatform platform,
            @NotNull AuthContext context,
            @Nullable String browserProofHash) {

        return recipientOf(account.id(), platform).thenCompose(recipient -> {
            if (recipient.isEmpty()) {
                // Привязки нет либо подтверждение входа для неё выключено. Раньше в
                // этом случае событие всё равно публиковалось, и игрок ждал кнопку,
                // которой никто не мог показать.
                return CompletableFuture.completedFuture(OperationResult.<LoginApproval>fail(
                        "APPROVAL_UNAVAILABLE",
                        "Подтверждение входа для этой платформы недоступно"));
            }
            Recipient target = recipient.get();
            Instant now = Instant.now();

            LoginApproval approval = new LoginApproval(
                    0L,
                    TokenGenerator.randomToken(PUBLIC_ID_BYTES),
                    account.id(),
                    account.username(),
                    playerUuid,
                    attemptId,
                    context.source(),
                    context.platform(),
                    context.userAgent(),
                    context.deviceFingerprint(),
                    platform,
                    target.recipientId(),
                    target.chatId(),
                    context.ip(),
                    context.server(),
                    context.country(),
                    context.city(),
                    ApprovalStatus.PENDING,
                    now,
                    now.plus(lifetime()),
                    null,
                    null,
                    browserProofHash,
                    null,
                    null);

            return approvals.insert(approval)
                    // Прежние кнопки того же аккаунта обесцениваются: несколько
                    // действующих запросов расширяют окно, в котором вход подтверждается.
                    .thenCompose(saved -> approvals
                            .supersedePending(account.id(), saved.publicId(), now)
                            .thenApply(ignored -> saved))
                    .thenCompose(saved -> enqueueRequest(saved).thenApply(ignored -> saved))
                    .thenCompose(saved -> audit.log(account.id(),
                                    SecurityEventType.LOGIN_APPROVAL_REQUESTED, context,
                                    "Запрошено подтверждение входа через " + platform)
                            .thenApply(ignored -> OperationResult.ok(saved)));
        });
    }

    /** Совместимый вызов для игровых/ботовых входов: у них нет browser proof. */
    public @NotNull CompletableFuture<OperationResult<LoginApproval>> request(
            @NotNull Account account, @Nullable UUID playerUuid, @NotNull String attemptId,
            @NotNull BotPlatform platform, @NotNull AuthContext context) {
        return request(account, playerUuid, attemptId, platform, context, null);
    }

    /** Кому адресована кнопка. */
    private record Recipient(long recipientId, long chatId) {
    }

    private CompletableFuture<Optional<Recipient>> recipientOf(long accountId, BotPlatform platform) {
        if (platform == BotPlatform.TELEGRAM) {
            return links.findTelegram(accountId).thenApply(binding -> binding
                    .filter(net.kofnetwork.auth.api.model.TelegramBinding::loginApprovalEnabled)
                    .map(b -> new Recipient(b.telegramId(), b.chatId())));
        }
        // У Discord отдельного чата нет: бот пишет пользователю в личные сообщения,
        // поэтому получатель и «чат» — один и тот же идентификатор.
        return links.findDiscord(accountId).thenApply(binding -> binding
                .filter(net.kofnetwork.auth.api.model.DiscordBinding::loginApprovalEnabled)
                .map(b -> new Recipient(b.discordId(), b.discordId())));
    }

    /**
     * Кладёт запрос в очередь бота.
     *
     * <p>Очередь, а не Pub/Sub: бот, перезапущенный на несколько секунд, обязан увидеть
     * запрос, пришедший в это время, — иначе игрок ждёт кнопку, которой не будет.
     */
    private CompletableFuture<Void> enqueueRequest(LoginApproval approval) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("approvalId", approval.publicId());
        payload.put("username", approval.username());
        // Адрес наружу уходит только маскированным: полный ничего не добавляет
        // владельцу, но попадает в логи мессенджера.
        payload.put("ip", approval.requestIp().asMasked());
        payload.put("expiresAt", approval.expiresAt().toString());
        if (approval.country() != null) {
            payload.put("country", approval.country());
        }
        if (approval.city() != null) {
            payload.put("city", approval.city());
        }
        if (approval.server() != null) {
            payload.put("server", approval.server());
        }

        return outbox.append(new BotMessage(0L, approval.platform(), approval.recipientId(),
                        approval.chatId(), BotMessage.Kind.LOGIN_APPROVAL, payload,
                        approval.issuedAt(), approval.expiresAt()))
                .thenApply(ignored -> (Void) null)
                .exceptionally(e -> {
                    // Запрос уже создан и виден по /api/bot; потеря сообщения означает
                    // лишь, что кнопка не появится сама — это не повод отменять вход.
                    LOGGER.warn("Не удалось поставить запрос подтверждения {} в очередь бота",
                            approval.publicId(), e);
                    return null;
                });
    }

    // ================================================================ решение

    @Override
    public @NotNull CompletableFuture<Decision> decide(@NotNull String approvalPublicId,
                                                        @NotNull BotPlatform pressedOn,
                                                        long pressedBy,
                                                        boolean approved) {
        Instant now = Instant.now();

        return approvals.findByPublicId(approvalPublicId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(Decision.of(DecisionResult.NOT_FOUND));
            }
            LoginApproval approval = found.get();

            // Владелец кнопки сверяется до всего остального и до любой записи в базу.
            // Без этой проверки любой пользователь бота, узнавший идентификатор запроса,
            // подтверждал бы чужой вход — а идентификатор виден в callback_data.
            if (!approval.belongsTo(pressedOn, pressedBy)) {
                return audit.log(approval.accountId(), SecurityEventType.LOGIN_APPROVAL_FOREIGN,
                                AuthContext.system(),
                                "Попытка подтвердить чужой вход: " + pressedOn + "/" + pressedBy)
                        .thenApply(ignored -> Decision.of(DecisionResult.FOREIGN));
            }
            if (approval.isExpired(now)) {
                return CompletableFuture.completedFuture(
                        new Decision(DecisionResult.EXPIRED, ApprovalStatus.EXPIRED,
                                approval.username()));
            }

            ApprovalStatus decision = approved ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED;
            return approvals.decide(approvalPublicId, decision, now, pressedBy)
                    .thenCompose(outcome -> outcome.applied()
                            ? applyDecision(outcome.approval(), decision)
                            : CompletableFuture.completedFuture(losingPress(outcome, now)));
        });
    }

    /**
     * Что ответить проигравшему нажатию.
     *
     * <p>Второе нажатие — обычное дело: кнопка остаётся в чате, и человек жмёт её ещё
     * раз, не увидев ответа. Отвечать «ошибка» неправильно; ответом служит уже
     * записанное решение.
     */
    private static Decision losingPress(LoginApprovalRepository.DecisionOutcome outcome,
                                        Instant now) {
        LoginApproval current = outcome.approval();
        if (current == null) {
            return Decision.of(DecisionResult.NOT_FOUND);
        }
        if (current.status() == ApprovalStatus.PENDING) {
            // Строка ещё PENDING, но UPDATE её не взял — значит, не прошло условие
            // по сроку. Планировщик пометит её EXPIRED позже.
            return new Decision(DecisionResult.EXPIRED, ApprovalStatus.EXPIRED, current.username());
        }
        DecisionResult result = current.status() == ApprovalStatus.EXPIRED
                ? DecisionResult.EXPIRED
                : DecisionResult.ALREADY_DECIDED;
        return new Decision(result, current.status(), current.username());
    }

    /** Доводит принятое решение до конца: завершает вход либо сообщает об отказе. */
    private CompletableFuture<Decision> applyDecision(@Nullable LoginApproval approval,
                                                       ApprovalStatus decision) {
        if (approval == null) {
            return CompletableFuture.completedFuture(Decision.of(DecisionResult.NOT_FOUND));
        }
        return (decision == ApprovalStatus.APPROVED ? approve(approval) : deny(approval))
                .thenApply(ignored -> new Decision(DecisionResult.APPLIED, decision,
                        approval.username()));
    }

    /**
     * Одобрение завершает <b>исходную</b> попытку входа.
     *
     * <p>Именно этого не делала прежняя версия: она создавала сессию типа
     * {@code TELEGRAM} из синтетического запроса, а игрок так и оставался в
     * {@code TWO_FACTOR_REQUIRED} до таймаута. Здесь создаётся игровая сессия для той
     * попытки, что ждёт решения, а событие сообщает прокси, кого и куда переводить.
     */
    private CompletableFuture<Void> approve(LoginApproval approval) {
        return completer.complete(approval)
                .exceptionally(e -> {
                    LOGGER.error("Не удалось завершить вход по подтверждению {}",
                            approval.publicId(), e);
                    return Optional.empty();
                })
                .thenCompose(session -> {
                    ApprovalStatus status = session.isPresent()
                            ? ApprovalStatus.APPROVED
                            : ApprovalStatus.DENIED;
                    if (session.isEmpty()) {
                        LOGGER.warn("Подтверждение {} одобрено, но сессия не создана",
                                approval.publicId());
                    }
                    CompletableFuture<Void> persisted = session.isPresent()
                            ? approvals.storeCompletedSession(approval.publicId(), session.get().id())
                            : CompletableFuture.completedFuture(null);
                    return persisted.thenCompose(ignored -> publishDecision(approval, status,
                            session.map(Session::publicId).orElse(null)));
                });
    }

    private CompletableFuture<Void> deny(LoginApproval approval) {
        // Отказ владельца важнее самого отказа во входе: он означает, что пароль
        // known кому-то ещё. Событие безопасности уходит независимо от того,
        // увидит ли игрок сообщение.
        return events.publish(SuspiciousActivityEvent.of(approval.accountId(),
                        SecurityEventType.LOGIN_APPROVAL_DENIED,
                        contextOf(approval),
                        "Владелец отклонил вход с адреса " + approval.requestIp().asMasked()))
                .thenCompose(ignored -> publishDecision(approval, ApprovalStatus.DENIED, null));
    }

    private CompletableFuture<Void> publishDecision(LoginApproval approval,
                                                     ApprovalStatus status,
                                                     @Nullable String sessionPublicId) {
        return notifyBot(approval, status)
                .thenCompose(ignored -> events.publish(LoginApprovalDecidedEvent.of(
                        approval.accountId(), approval.username(), approval.playerUuid(),
                        approval.attemptId(), approval.publicId(), approval.platform(),
                        status, sessionPublicId)));
    }

    /** Сообщает боту, что кнопку можно убрать. */
    private CompletableFuture<Void> notifyBot(LoginApproval approval, ApprovalStatus status) {
        Map<String, String> payload = Map.of(
                "approvalId", approval.publicId(),
                "username", approval.username(),
                "status", status.name());

        return outbox.append(new BotMessage(0L, approval.platform(), approval.recipientId(),
                        approval.chatId(), BotMessage.Kind.LOGIN_APPROVAL_RESOLVED, payload,
                        Instant.now(), Instant.now().plus(Duration.ofMinutes(10))))
                .thenApply(ignored -> (Void) null)
                .exceptionally(e -> {
                    LOGGER.warn("Не удалось сообщить боту об исходе подтверждения {}",
                            approval.publicId(), e);
                    return null;
                });
    }

    private static AuthContext contextOf(LoginApproval approval) {
        return new AuthContext(approval.requestIp(), approval.requestSource(),
                approval.requestPlatform(), approval.userAgent(), approval.deviceFingerprint(),
                approval.country(), approval.city(), null, approval.server(), null, null);
    }

    @Override
    public @NotNull CompletableFuture<Optional<LoginApproval>> find(@NotNull String approvalPublicId) {
        return approvals.findByPublicId(approvalPublicId);
    }

    @Override
    public @NotNull CompletableFuture<WebAttempt> webStatus(@NotNull String attemptId,
                                                              @NotNull String browserProof) {
        String proofHash = TokenGenerator.hash(browserProof);
        return approvals.findByAttemptId(attemptId).thenApply(found -> found
                .filter(approval -> approval.requestSource() == EventSource.WEB)
                .filter(approval -> approval.browserProofHash() != null)
                .filter(approval -> TokenGenerator.constantTimeEquals(
                        approval.browserProofHash(), proofHash))
                .map(approval -> {
                    boolean ready = approval.status() == ApprovalStatus.APPROVED
                            && approval.sessionId() != null
                            && approval.exchangeConsumedAt() == null;
                    // Между атомарным решением и созданием сессии есть короткое
                    // нормальное окно. Браузер должен продолжать ждать, а не
                    // интерпретировать его как успешный обмен без сессии. Если
                    // создание так и не завершилось до дедлайна, попытка больше
                    // не может быть безопасно выдана и выглядит как истёкшая.
                    ApprovalStatus status = approval.status() == ApprovalStatus.APPROVED
                            && !ready && Instant.now().isAfter(approval.expiresAt())
                            ? ApprovalStatus.EXPIRED : approval.status();
                    return new WebAttempt(status, ready);
                })
                // Не раскрываем существование чужой попытки: proof обладает той же
                // ролью, что anti-CSRF secret конкретной вкладки.
                .orElse(new WebAttempt(ApprovalStatus.EXPIRED, false)));
    }

    @Override
    public @NotNull CompletableFuture<WebExchange> exchangeWeb(@NotNull String attemptId,
                                                                 @NotNull String browserProof) {
        return approvals.consumeWebExchange(attemptId, TokenGenerator.hash(browserProof), Instant.now())
                .thenApply(outcome -> {
                    LoginApproval approval = outcome.approval();
                    if (approval == null || approval.requestSource() != EventSource.WEB
                            || approval.browserProofHash() == null
                            || !TokenGenerator.constantTimeEquals(approval.browserProofHash(),
                                    TokenGenerator.hash(browserProof))) {
                        return new WebExchange(false, null, null, ApprovalStatus.EXPIRED);
                    }
                    return new WebExchange(outcome.consumed(), approval.accountId(),
                            approval.sessionId(), approval.status());
                });
    }
}
