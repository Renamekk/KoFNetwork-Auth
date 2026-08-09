package net.kofnetwork.auth.paper;

import net.kofnetwork.auth.api.KoFAuthProvider;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.paper.captcha.CaptchaGuiManager;
import net.kofnetwork.auth.paper.captcha.ChatCaptchaRenderer;
import net.kofnetwork.auth.paper.captcha.GuiCaptchaRenderer;
import net.kofnetwork.auth.paper.command.CaptchaCommand;
import net.kofnetwork.auth.paper.listener.LimboProtectionListener;
import net.kofnetwork.auth.paper.listener.LockdownListener;
import net.kofnetwork.auth.paper.world.LimboWorldFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Плагин Paper. Работает в одном из двух режимов, задаваемых {@code paper.yml}.
 *
 * <p><b>LIMBO</b> — сюда попадает игрок до аутентификации: пустой замороженный мир,
 * защита от любого взаимодействия, CAPTCHA.
 *
 * <p><b>BACKEND</b> — обычный игровой сервер: только проверка сессии на входе,
 * чтобы прямое подключение в обход прокси не давало доступа.
 *
 * <p><b>Экземпляр Core.</b> Если прокси уже поднял Core в этой JVM (что бывает
 * только в тестах), плагин переиспользует его. В обычном развёртывании Paper —
 * отдельный процесс и поднимает собственный Core.
 */
public final class KoFAuthPaper extends JavaPlugin implements Listener {

    private KoFAuthCore core;
    private boolean ownsCore;
    private Mode mode;
    private LimboProtectionListener protection;
    private CaptchaGuiManager captchaGui;
    private World limboWorld;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /** Роль сервера в сети. */
    public enum Mode {
        LIMBO, BACKEND
    }

    @Override
    public void onEnable() {
        try {
            core = KoFAuthProvider.isAvailable()
                    ? (KoFAuthCore) KoFAuthProvider.get()
                    : startOwnCore();
        } catch (RuntimeException e) {
            lockdown("KoFAuth не запустился: " + e.getMessage(), e);
            return;
        }

        mode = parseMode();
        getLogger().info("Режим работы: " + mode);

        if (mode == Mode.LIMBO) {
            setupLimbo();
        }

        getServer().getPluginManager().registerEvents(this, this);
        startReadinessWatch();
        getLogger().info("KoFAuth запущен");
    }

    /**
     * Закрывает сервер, когда аутентификация не работает.
     *
     * <p><b>Почему одного {@code disablePlugin} мало.</b> Прежде отказ запуска
     * выключал плагин — и на этом всё. Сервер оставался работать: слушал порт,
     * принимал подключения и никак их не проверял. Прямое подключение в обход
     * прокси попадало на живой игровой мир под любым ником. Выключенный плагин
     * защиты не защищает.
     *
     * <p>Порядок здесь важен. Сначала регистрируется отсечка, отвергающая любые
     * новые подключения, затем отключаются уже подключённые, и только потом
     * останавливается сервер: между решением и фактической остановкой проходят
     * секунды, и в это окно иначе успевал бы зайти кто угодно.
     *
     * <p>Остановку можно отключить настройкой {@code fail-closed.shutdown} — тогда
     * сервер останется поднятым, но в блокировке. Это уместно лишь там, где рядом
     * стоит внешний надзор, который увидит отказ и вмешается.
     */
    private void lockdown(String reason, Throwable cause) {
        getLogger().severe("Сервер переводится в блокировку. " + reason);
        if (cause != null) {
            getLogger().severe(cause.toString());
        }

        LockdownListener guard = new LockdownListener(
                "<red>Сервер авторизации недоступен. Зайдите позже.");
        getServer().getPluginManager().registerEvents(guard, this);
        getServer().getScheduler().runTask(this,
                () -> guard.kickEveryone(getServer().getOnlinePlayers()));

        boolean shutdown = core == null
                || core.config().getBoolean(ConfigFile.PAPER, "fail-closed.shutdown", true);
        if (shutdown) {
            getLogger().severe("Останавливаю сервер: работать без аутентификации нельзя.");
            getServer().getScheduler().runTask(this, () -> getServer().shutdown());
        }
    }

    /**
     * Следит, что аутентификация продолжает работать.
     *
     * <p>Отказ базы или хранилища состояния уже после запуска ничем не лучше отказа
     * при старте: сервер так же перестаёт отличать вошедшего игрока от невошедшего.
     * Порог в несколько проверок подряд нужен, чтобы секундная помеха не роняла
     * сервер, на котором играют люди.
     */
    private void startReadinessWatch() {
        if (!core.config().getBoolean(ConfigFile.PAPER, "fail-closed.watch-readiness", true)) {
            return;
        }
        long ticks = Math.max(100L, core.config()
                .getDuration(ConfigFile.PAPER, "fail-closed.check-interval", Duration.ofSeconds(15))
                .toSeconds() * 20L);
        int threshold = Math.max(1, core.config()
                .getInt(ConfigFile.PAPER, "fail-closed.failures-before-lockdown", 4));

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (core.isReady()) {
                readinessFailures.set(0);
                return;
            }
            int failures = readinessFailures.incrementAndGet();
            getLogger().warning("KoFAuth не готов (" + core.readinessDetail()
                    + "), подряд отказов: " + failures + " из " + threshold);
            if (failures >= threshold) {
                lockdown("зависимости недоступны: " + core.readinessDetail(), null);
            }
        }, ticks, ticks);
    }

    /** Сколько проверок готовности подряд не прошло. */
    private final java.util.concurrent.atomic.AtomicInteger readinessFailures =
            new java.util.concurrent.atomic.AtomicInteger();

    private KoFAuthCore startOwnCore() {
        ownsCore = true;
        return KoFAuthCore.start(getDataFolder().toPath());
    }

    private Mode parseMode() {
        String raw = core.config().getString(ConfigFile.PAPER, "mode", "BACKEND");
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            getLogger().warning("Неизвестный режим '" + raw + "', используется BACKEND");
            return Mode.BACKEND;
        }
    }

    private void setupLimbo() {
        String worldName = core.config().getString(ConfigFile.PAPER, "limbo.world-name",
                "kofauth_limbo");
        long fixedTime = core.config().getLong(ConfigFile.PAPER, "limbo.effects.fixed-time", 6000);
        boolean noWeather = core.config().getBoolean(ConfigFile.PAPER,
                "limbo.effects.disable-weather", true);

        limboWorld = LimboWorldFactory.loadOrCreate(worldName, fixedTime, noWeather,
                getLogger());
        LimboWorldFactory.ensureFloor(spawnLocation(),
                core.config().getInt(ConfigFile.PAPER, "limbo.spawn.platform-radius", 3));

        protection = new LimboProtectionListener(core, this);
        getServer().getPluginManager().registerEvents(protection, this);

        // Регистрируем средства отображения CAPTCHA: сервис в Core о Bukkit не знает.
        GuiCaptchaRenderer guiRenderer = new GuiCaptchaRenderer(
                core.config().getString(ConfigFile.CAPTCHA, "gui.title",
                        "<dark_gray>Подтвердите, что вы не бот"),
                core.config().getInt(ConfigFile.CAPTCHA, "gui.grid-size", 4)
                        * core.config().getInt(ConfigFile.CAPTCHA, "gui.rows", 3));
        core.captcha().registerRenderer(guiRenderer);
        core.captcha().registerRenderer(new ChatCaptchaRenderer());

        captchaGui = new CaptchaGuiManager(core, this, guiRenderer);
        getServer().getPluginManager().registerEvents(captchaGui, this);

        var captchaCommand = getCommand("captcha");
        if (captchaCommand != null) {
            CaptchaCommand executor = new CaptchaCommand(core, captchaGui);
            captchaCommand.setExecutor(executor);
            captchaCommand.setTabCompleter(executor);
        }

        startReminder();
        startLimboTitle();
    }

    /**
     * Держит на экране название сети, пока игрок не прошёл вход.
     *
     * <p><b>Почему это повторяющаяся задача, а не одна отправка.</b> Титул в
     * Minecraft живёт заданное число тиков и гаснет сам; протокол не умеет
     * «показывать, пока не сниму». Поэтому он переотправляется с периодом,
     * заведомо меньшим времени показа, — тогда следующая отправка приходит до
     * того, как погасла предыдущая, и надпись выглядит неподвижной. Плавное
     * появление задаётся только первой отправке: повтор с ненулевым fade-in
     * заставлял бы надпись пульсировать.
     *
     * <p>Подзаголовок зависит от шага входа: пока не пройдена CAPTCHA — про неё,
     * дальше — про пароль или регистрацию. Игрок всегда видит, чего от него
     * ждут, даже если пропустил сообщение в чате.
     */
    private void startLimboTitle() {
        if (!core.config().getBoolean(ConfigFile.PAPER, "limbo.title.enabled", true)) {
            return;
        }
        Duration refresh = core.config().getDuration(ConfigFile.PAPER,
                "limbo.title.refresh", Duration.ofSeconds(2));
        long ticks = Math.max(20L, refresh.toMillis() / 50L);

        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                core.sessions().getState(uuid).thenAccept(state -> {
                    if (state.isAuthenticated()) {
                        titled.remove(uuid);
                        return;
                    }
                    showLimboTitle(player, state);
                });
            }
        }, 20L, ticks);
    }

    /** Кому титул уже показывался: определяет, нужно ли плавное появление. */
    private final java.util.Set<UUID> titled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void showLimboTitle(Player player, AuthState state) {
        UUID uuid = player.getUniqueId();
        boolean first = titled.add(uuid);

        Component header = parse("limbo-title",
                "<gradient:#4facfe:#00f2fe><bold>KoF Network</bold></gradient>");
        Component subtitle = parse("limbo-subtitle-" + subtitleKey(player, state),
                defaultSubtitle(state));

        Title.Times times = Title.Times.times(
                first ? duration("limbo.title.fade-in", Duration.ofMillis(250)) : Duration.ZERO,
                duration("limbo.title.stay", Duration.ofSeconds(5)),
                duration("limbo.title.fade-out", Duration.ofMillis(250)));

        // Adventure в Paper потокобезопасен, отправка из асинхронного колбэка
        // не требует возврата в главный поток.
        player.showTitle(Title.title(header, subtitle, times));
    }

    /**
     * Какой подзаголовок показать.
     *
     * <p>Открытая задача CAPTCHA важнее состояния из Redis: окно уже на экране,
     * и предлагать в этот момент ввести пароль значит противоречить самому себе.
     */
    private String subtitleKey(Player player, AuthState state) {
        if (state == AuthState.CAPTCHA_REQUIRED
                || (captchaGui != null && captchaGui.hasActive(player.getUniqueId()))) {
            return "captcha";
        }
        return state == AuthState.AWAITING_REGISTER ? "register" : "login";
    }

    private static String defaultSubtitle(AuthState state) {
        return state == AuthState.AWAITING_REGISTER
                ? "<yellow>Зарегистрируйтесь: <white>/register <пароль> <пароль>"
                : "<yellow>Войдите: <white>/login <пароль>";
    }

    private Duration duration(String path, Duration fallback) {
        return core.config().getDuration(ConfigFile.PAPER, path, fallback);
    }

    /**
     * Периодическое напоминание о необходимости войти.
     *
     * <p>Выполняется в главном потоке — отправка сообщения требует этого, — но
     * читает только локальный кэш состояний, без обращения к Redis или базе.
     */
    private void startReminder() {
        if (!core.config().getBoolean(ConfigFile.PAPER, "limbo.reminder.enabled", true)) {
            return;
        }
        Duration interval = core.config().getDuration(ConfigFile.PAPER,
                "limbo.reminder.interval", Duration.ofSeconds(10));
        long ticks = Math.max(20L, interval.toSeconds() * 20L);

        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                core.sessions().getState(player.getUniqueId()).thenAccept(state -> {
                    if (state.isAuthenticated()) {
                        return;
                    }
                    // Прокси мог перевести игрока в CAPTCHA_REQUIRED после неудачной
                    // попытки входа. Окно открывается здесь, потому что GUI живёт
                    // на Paper, а решение принял другой процесс.
                    if (state == AuthState.CAPTCHA_REQUIRED) {
                        if (!captchaGui.hasActive(player.getUniqueId())) {
                            getServer().getScheduler().runTask(this,
                                    () -> maybeIssueCaptcha(player));
                        }
                        return;
                    }
                    Component message = state == AuthState.AWAITING_REGISTER
                            ? parse("register-prompt",
                                    "<yellow>Зарегистрируйтесь: <white>/register <пароль> <пароль>")
                            : parse("login-prompt", "<yellow>Войдите: <white>/login <пароль>");
                    // Отправка сообщения из асинхронного колбэка допустима:
                    // Adventure в Paper потокобезопасен.
                    player.sendActionBar(message);
                });
            }
        }, ticks, ticks);
    }

    /** Текст из {@code messages.yml}. */
    private Component parse(String path, String fallback) {
        return miniMessage.deserialize(
                core.config().getString(ConfigFile.MESSAGES, path, fallback));
    }

    // ------------------------------------------------------------------ события

    /**
     * Определение состояния игрока при входе на сервер.
     *
     * <p><b>Проверка без побочных эффектов.</b> Используется
     * {@code hasValidSession}, а не {@code validate}. Второй сверяет адрес игрока
     * с адресом сессии и при несовпадении считает это угоном: отзывает сессию и
     * публикует событие. На Paper это ломается о факт, что
     * {@link Player#getAddress()} на раннем этапе входа может вернуть
     * {@code null} — тогда адрес превращался в {@code 0.0.0.0}, совпадения не
     * было, и проверка доступа сама уничтожала действующую сессию: игрок
     * отключался, а вернуться мог только через повторный ввод пароля. Сверку
     * адреса выполняет прокси, где адрес известен всегда.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        core.sessions().hasValidSession(player.getUniqueId()).thenAccept(authenticated -> {
            if (mode == Mode.BACKEND) {
                handleBackendJoin(player, authenticated);
                return;
            }
            handleLimboJoin(player, authenticated);
        }).exceptionally(e -> {
            // Проверка не удалась. На Limbo это безопасно: игрок останется под
            // защитой. На бэкенде — отключаем, иначе отказ Redis открывал бы
            // игровой сервер для прямых подключений в обход прокси.
            getLogger().warning("Не удалось проверить сессию игрока "
                    + player.getName() + ": " + e);
            if (mode == Mode.BACKEND) {
                handleBackendJoin(player, false);
            } else {
                handleLimboJoin(player, false);
            }
            return null;
        });
    }

    /**
     * Проверка сессии на игровом сервере.
     *
     * <p>Защита от прямого подключения в обход прокси. При корректной настройке
     * {@code player-info-forwarding} сюда попасть нельзя, но полагаться на одну лишь
     * конфигурацию сети для контроля доступа не стоит.
     */
    private void handleBackendJoin(Player player, boolean authenticated) {
        if (authenticated || !core.config().getBoolean(ConfigFile.PAPER,
                "backend.verify-session", true)) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> player.kick(
                miniMessage.deserialize(core.config().getString(ConfigFile.MESSAGES,
                        "kick.not-authenticated",
                        "<red>Вы не прошли аутентификацию."))));
    }

    private void handleLimboJoin(Player player, boolean authenticated) {
        AuthState state = authenticated ? AuthState.AUTHENTICATED : AuthState.CONNECTING;

        getServer().getScheduler().runTask(this, () -> {
            player.setGameMode(GameMode.ADVENTURE);
            player.setInvulnerable(true);
            player.getInventory().clear();
            player.setFoodLevel(20);
            player.setLevel(0);
            player.setExp(0f);

            if (limboWorld != null) {
                player.teleport(spawnLocation());
            }
            if (core.config().getBoolean(ConfigFile.PAPER, "limbo.protection.hide-players", true)) {
                hideFromEveryone(player);
            }
            protection.track(player, state);

            if (!authenticated) {
                maybeIssueCaptcha(player);
            }
        });
    }

    /**
     * Выдаёт CAPTCHA, если она требуется этому игроку.
     *
     * <p>Проверка идёт по аккаунту: новому игроку задача выдаётся при
     * {@code require-on.every-register}, существующему — при первом входе либо
     * при подозрительной активности. Решение принимает сервис в Core, здесь
     * только вызов.
     */
    private void maybeIssueCaptcha(Player player) {
        IpAddress ip = IpAddress.ofNullable(player.getAddress() == null
                ? null : player.getAddress().getAddress().getHostAddress());
        var context = net.kofnetwork.auth.api.dto.AuthContext
                .minecraft(ip, getServer().getName(), null, null);

        core.authentication().findAccount(player.getName())
                .thenCompose(account -> {
                    Long accountId = account.map(a -> a.id()).orElse(null);
                    return core.captcha().isRequired(accountId, context)
                            .thenAccept(required -> {
                                if (required) {
                                    captchaGui.issue(player, accountId);
                                }
                            });
                })
                .exceptionally(e -> {
                    // Отказ проверки не должен запирать игрока: без CAPTCHA он
                    // всё равно упрётся в пароль.
                    getLogger().warning("Не удалось определить необходимость CAPTCHA для "
                            + player.getName() + ": " + e);
                    return null;
                });
    }

    private Location spawnLocation() {
        return new Location(limboWorld,
                core.config().getDouble(ConfigFile.PAPER, "limbo.spawn.x", 0.5),
                core.config().getDouble(ConfigFile.PAPER, "limbo.spawn.y", 100.0),
                core.config().getDouble(ConfigFile.PAPER, "limbo.spawn.z", 0.5),
                (float) core.config().getDouble(ConfigFile.PAPER, "limbo.spawn.yaw", 0.0),
                (float) core.config().getDouble(ConfigFile.PAPER, "limbo.spawn.pitch", 0.0));
    }

    /**
     * Прячет игроков друг от друга.
     *
     * <p>Мешает подсмотреть, кто вводит пароль, и заодно снимает нагрузку с клиента
     * при массовом заходе ботов: сотня видимых сущностей в Limbo никому не нужна.
     */
    private void hideFromEveryone(Player player) {
        for (Player other : getServer().getOnlinePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            player.hidePlayer(this, other);
            other.hidePlayer(this, player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (protection != null) {
            protection.forget(uuid);
        }
        if (captchaGui != null) {
            captchaGui.forget(uuid);
        }
        // Иначе множество растёт на каждого зашедшего и никогда не уменьшается,
        // а вернувшийся игрок не увидит плавного появления надписи.
        titled.remove(uuid);
    }

    @Override
    public void onDisable() {
        // Core закрывается только если этот плагин его и поднял: в общей JVM
        // с прокси владелец — прокси, и закрывать чужой экземпляр нельзя.
        if (core != null && ownsCore) {
            core.close();
        }
        getLogger().info("KoFAuth остановлен");
    }

    /** Экземпляр Core — другим плагинам сети. */
    public @NotNull KoFAuthCore core() {
        return core;
    }

    /** Режим работы сервера. */
    public @NotNull Mode mode() {
        return mode;
    }
}
