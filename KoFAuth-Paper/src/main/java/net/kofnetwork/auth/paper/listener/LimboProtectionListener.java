package net.kofnetwork.auth.paper.listener;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.core.KoFAuthCore;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Защита неаутентифицированного игрока в Limbo.
 *
 * <p><b>Почему состояние кэшируется в памяти.</b> Слушатели событий движения и
 * взаимодействия вызываются десятки раз в секунду на каждого игрока. Обращаться
 * за состоянием в Redis из главного потока на каждом таком событии нельзя — это
 * ровно то, ради предотвращения чего написан весь асинхронный слой. Поэтому
 * состояние читается один раз при входе и держится в {@link ConcurrentHashMap};
 * авторитетное значение по-прежнему в Redis, здесь лишь его отражение.
 *
 * <p><b>Почему дублируется с Velocity.</b> Прокси блокирует чат и команды, но не
 * ломание блоков и не урон — эти события существуют только на бэкенде. Плюс защита
 * от прямого подключения к Paper в обход прокси, если {@code online-mode} и
 * {@code player-info-forwarding} настроены неверно.
 */
public final class LimboProtectionListener implements Listener {

    private final KoFAuthCore core;
    private final ConfigurationService config;
    private final Plugin plugin;

    /** Отражение состояния входа. Авторитетное значение — в Redis. */
    private final Map<UUID, AuthState> states = new ConcurrentHashMap<>();

    /** Точка появления: от неё считается разрешённый радиус перемещения. */
    private final Map<UUID, Location> anchors = new ConcurrentHashMap<>();

    public LimboProtectionListener(@NotNull KoFAuthCore core, @NotNull Plugin plugin) {
        this.core = core;
        this.config = core.config();
        this.plugin = plugin;
    }

    /** Запоминает состояние игрока. Вызывается при входе на сервер. */
    public void track(@NotNull Player player, @NotNull AuthState state) {
        states.put(player.getUniqueId(), state);
        anchors.put(player.getUniqueId(), player.getLocation().clone());
    }

    /** Обновляет состояние после успешного входа. */
    public void update(@NotNull UUID uuid, @NotNull AuthState state) {
        states.put(uuid, state);
    }

    /** Забывает игрока при отключении. */
    public void forget(@NotNull UUID uuid) {
        states.remove(uuid);
        anchors.remove(uuid);
    }

    /** Требуется ли защищать этого игрока. */
    private boolean isProtected(Player player) {
        AuthState state = states.get(player.getUniqueId());
        // Отсутствие записи трактуется как «не аутентифицирован»: если состояние
        // ещё не загрузилось, безопаснее ограничить, чем пропустить.
        return state == null || !state.isAuthenticated();
    }

    private boolean enabled(String key) {
        return config.getBoolean(ConfigFile.PAPER, "limbo.protection." + key, true);
    }

    /** Отменяет событие, если игрок под защитой и правило включено. */
    private void cancelIfProtected(Cancellable event, Player player, String rule) {
        if (isProtected(player) && enabled(rule)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ мир

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        cancelIfProtected(event, event.getPlayer(), "block-break");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        cancelIfProtected(event, event.getPlayer(), "block-place");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        cancelIfProtected(event, event.getPlayer(), "interact");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        cancelIfProtected(event, event.getPlayer(), "interact");
    }

    // ------------------------------------------------------------------ урон и здоровье

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelIfProtected(event, player, "damage");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelIfProtected(event, player, "hunger");
        }
    }

    /**
     * Смерть в Limbo невозможна по построению (урон отключён), но если она
     * всё же произошла — экран смерти у неаутентифицированного игрока
     * заблокирует ввод команд, и войти он не сможет.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isProtected(player)) {
            return;
        }
        event.setCancelled(true);
        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                ? 20.0
                : player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
    }

    // ------------------------------------------------------------------ предметы

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        cancelIfProtected(event, event.getPlayer(), "item-drop");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        cancelIfProtected(event, event.getPlayer(), "item-pickup");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        cancelIfProtected(event, event.getPlayer(), "item-drop");
    }

    /**
     * Клики в инвентаре.
     *
     * <p>Исключение — GUI CAPTCHA: в нём кликать как раз нужно. Отличается по
     * заголовку окна, потому что титул задаётся нами и не может совпасть случайно.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isProtected(player)) {
            return;
        }
        if (net.kofnetwork.auth.paper.captcha.GuiCaptchaRenderer.isCaptchaInventory(
                event.getView().getTitle())) {
            return;
        }
        if (enabled("item-pickup")) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ чат и команды

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent event) {
        // Дублирует блокировку на прокси: игрок, перепутавший /login с обычным
        // сообщением, не должен отправить пароль в общий чат ни при каких условиях.
        if (isProtected(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isProtected(player) || !enabled("commands")) {
            return;
        }
        String root = event.getMessage().split(" ", 2)[0]
                .substring(1)
                .toLowerCase(Locale.ROOT);

        boolean allowed = config
                .getStringList(ConfigFile.VELOCITY, "restrictions.allowed-commands").stream()
                .anyMatch(name -> name.equalsIgnoreCase(root));
        if (!allowed) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ перемещение

    /**
     * Ограничение перемещения радиусом.
     *
     * <p>Полная блокировка движения раздражает и выглядит как лаг, поэтому по
     * умолчанию игрок может ходить в пределах радиуса. Сравнение идёт по квадрату
     * расстояния — извлекать корень на каждом движении каждого игрока незачем.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isProtected(player)) {
            return;
        }
        if (enabled("movement")) {
            // Полная блокировка: разрешён только поворот головы.
            if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                    || event.getFrom().getBlockZ() != event.getTo().getBlockZ()
                    || event.getFrom().getBlockY() != event.getTo().getBlockY()) {
                event.setTo(event.getFrom());
            }
            return;
        }

        Location anchor = anchors.get(player.getUniqueId());
        if (anchor == null || !anchor.getWorld().equals(event.getTo().getWorld())) {
            return;
        }
        double radius = config.getDouble(ConfigFile.PAPER, "limbo.protection.movement-radius", 5.0);
        if (event.getTo().distanceSquared(anchor) > radius * radius) {
            event.setTo(anchor.clone());
        }
    }

    /** Телепортация неаутентифицированного игрока за пределы Limbo запрещена. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isProtected(player)) {
            return;
        }
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            // Наши собственные телепортации (возврат на точку появления) разрешены.
            return;
        }
        event.setCancelled(true);
    }
}
