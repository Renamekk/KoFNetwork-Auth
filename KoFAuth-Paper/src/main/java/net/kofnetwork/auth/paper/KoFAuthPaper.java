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
import net.kofnetwork.auth.paper.world.LimboWorldFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
            // Сервер без работающей аутентификации опаснее выключенного:
            // он пустит кого угодно под чужим ником.
            getLogger().severe("KoFAuth не запустился, плагин выключается: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        mode = parseMode();
        getLogger().info("Режим работы: " + mode);

        if (mode == Mode.LIMBO) {
            setupLimbo();
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("KoFAuth запущен");
    }

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

    private Component parse(String path, String fallback) {
        return miniMessage.deserialize(
                core.config().getString(ConfigFile.CONFIG, "messages." + path, fallback));
    }

    // ------------------------------------------------------------------ события

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        IpAddress ip = IpAddress.ofNullable(
                player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress());

        core.sessions().validate(player.getUniqueId(), ip).thenAccept(session -> {
            boolean authenticated = session.isPresent();

            if (mode == Mode.BACKEND) {
                handleBackendJoin(player, authenticated);
                return;
            }
            handleLimboJoin(player, authenticated);
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
                miniMessage.deserialize(core.config().getString(ConfigFile.VELOCITY,
                        "kick-messages.not-authenticated",
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
        if (protection != null) {
            protection.forget(event.getPlayer().getUniqueId());
        }
        if (captchaGui != null) {
            captchaGui.forget(event.getPlayer().getUniqueId());
        }
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
