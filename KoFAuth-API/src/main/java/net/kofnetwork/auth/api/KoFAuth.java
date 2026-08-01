package net.kofnetwork.auth.api;

import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.event.EventBus;
import net.kofnetwork.auth.api.service.AuditService;
import net.kofnetwork.auth.api.service.AuthenticationService;
import net.kofnetwork.auth.api.service.CaptchaService;
import net.kofnetwork.auth.api.service.EmailService;
import net.kofnetwork.auth.api.service.LinkService;
import net.kofnetwork.auth.api.service.RegistrationService;
import net.kofnetwork.auth.api.service.SecurityService;
import net.kofnetwork.auth.api.service.SessionService;
import net.kofnetwork.auth.api.service.TokenService;
import net.kofnetwork.auth.api.service.TotpService;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

/**
 * Единая точка доступа ко всем службам KoFAuth.
 *
 * <p>Реализуется классом {@code KoFAuthCore} и передаётся платформенным модулям при
 * старте. Фасад существует затем, чтобы плагину не приходилось собирать граф из
 * десятка сервисов вручную и чтобы порядок их создания оставался внутренним делом Core.
 *
 * <p>Это не сервис-локатор в дурном смысле: объект передаётся явно через конструктор
 * (пункт {@link KoFAuthProvider} — вынужденная уступка платформам, где конструктором
 * управляет не наш код), а состав служб фиксирован интерфейсом, а не строковыми ключами.
 */
public interface KoFAuth {

    @NotNull AuthenticationService authentication();

    @NotNull RegistrationService registration();

    @NotNull SessionService sessions();

    @NotNull SecurityService security();

    @NotNull CaptchaService captcha();

    @NotNull EmailService email();

    @NotNull TotpService totp();

    @NotNull LinkService links();

    @NotNull TokenService tokens();

    @NotNull AuditService audit();

    @NotNull EventBus events();

    @NotNull ConfigurationService config();

    /**
     * Пул для операций ввода-вывода вне главного потока Minecraft.
     *
     * <p>Отдаётся наружу, чтобы платформенные модули не заводили собственные пулы:
     * пять плагинов с пятью {@code Executors.newFixedThreadPool(10)} — это пятьдесят
     * потоков там, где хватает десяти.
     */
    @NotNull Executor ioExecutor();

    /** Версия KoFAuth. */
    @NotNull String version();

    /** Готова ли система обслуживать запросы: подняты база и, если включён, кэш. */
    boolean isReady();
}
