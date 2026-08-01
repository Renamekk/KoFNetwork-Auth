package net.kofnetwork.auth.paper.captcha;

import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.dto.AuthContext;
import net.kofnetwork.auth.api.model.AuthState;
import net.kofnetwork.auth.api.model.CaptchaChallenge;
import net.kofnetwork.auth.api.model.IpAddress;
import net.kofnetwork.auth.api.service.CaptchaService;
import net.kofnetwork.auth.core.KoFAuthCore;
import net.kofnetwork.auth.core.service.impl.CaptchaServiceImpl;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Выдача CAPTCHA игроку и обработка ответа.
 *
 * <p>Связывает платформо-независимый {@link CaptchaService} с интерфейсом Paper:
 * сервис решает, нужна ли проверка и верен ли ответ, а этот класс открывает окно
 * и переводит клик по ячейке в строку ответа.
 *
 * <p><b>Открытый ответ здесь неизвестен.</b> Менеджер знает только раскладку —
 * какой предмет в какой ячейке. Проверку выполняет сервис сравнением хэшей, поэтому
 * даже полный доступ к коду плагина не даёт способа «подсмотреть» верную ячейку
 * в рантайме.
 */
public final class CaptchaGuiManager implements Listener {

    private final KoFAuthCore core;
    private final Plugin plugin;
    private final GuiCaptchaRenderer renderer;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /** Открытые задачи: игрок → идентификатор челленджа. */
    private final Map<UUID, String> active = new ConcurrentHashMap<>();

    /** Игроки, которым окно сейчас открывается: защита от повторной выдачи. */
    private final Map<UUID, Boolean> issuing = new ConcurrentHashMap<>();

    public CaptchaGuiManager(@NotNull KoFAuthCore core,
                             @NotNull Plugin plugin,
                             @NotNull GuiCaptchaRenderer renderer) {
        this.core = core;
        this.plugin = plugin;
        this.renderer = renderer;
    }

    /** Есть ли у игрока незакрытая задача. */
    public boolean hasActive(@NotNull UUID playerUuid) {
        return active.containsKey(playerUuid) || issuing.containsKey(playerUuid);
    }

    /**
     * Выдаёт задачу и открывает окно.
     *
     * <p>Повторный вызов при уже открытой задаче игнорируется: иначе периодическая
     * проверка состояния переоткрывала бы окно каждую секунду, и кликнуть было бы
     * невозможно.
     */
    public void issue(@NotNull Player player, @Nullable Long accountId) {
        UUID uuid = player.getUniqueId();
        if (hasActive(uuid)) {
            return;
        }
        issuing.put(uuid, Boolean.TRUE);

        AuthContext context = contextOf(player);

        core.captcha().issue(accountId, uuid, null, context)
                .thenAccept(challenge -> Bukkit.getScheduler().runTask(plugin,
                        () -> open(player, challenge)))
                .exceptionally(e -> {
                    issuing.remove(uuid);
                    plugin.getLogger().severe(
                            "Не удалось выдать CAPTCHA игроку " + player.getName() + ": " + e);
                    return null;
                });
    }

    /** Открывает окно с разложенной задачей. Выполняется в главном потоке. */
    private void open(Player player, CaptchaChallenge challenge) {
        UUID uuid = player.getUniqueId();
        issuing.remove(uuid);

        if (!player.isOnline()) {
            return;
        }

        CaptchaService.RenderedCaptcha rendered =
                ((CaptchaServiceImpl) core.captcha()).render(challenge);

        if (rendered == null) {
            // Раскладка недоступна (нет рендерера либо потерян открытый ответ).
            // Держать игрока с невыполнимым требованием нельзя.
            plugin.getLogger().warning("Нет раскладки для задачи " + challenge.challengeId()
                    + ", CAPTCHA пропущена для " + player.getName());
            passThrough(player);
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, renderer.cells(),
                miniMessage.deserialize(renderer.title()));

        List<String> options = rendered.options();
        for (int slot = 0; slot < renderer.cells() && slot < options.size(); slot++) {
            Material material = Material.matchMaterial(options.get(slot));
            inventory.setItem(slot, new ItemStack(
                    material == null ? Material.STONE : material));
        }

        active.put(uuid, challenge.challengeId());
        player.openInventory(inventory);
        player.sendMessage(prefixed(rendered.prompt()));

        String sound = core.config().getString(ConfigFile.PAPER, "gui.open-sound", "");
        playSound(player, sound);
    }

    /**
     * Обработка клика по ячейке.
     *
     * <p>Событие отменяется всегда: предметы в окне — кнопки, а не инвентарь.
     * Дать их взять означало бы позволить вынести предмет в Limbo.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String challengeId = active.get(player.getUniqueId());
        if (challengeId == null) {
            return;
        }
        if (!GuiCaptchaRenderer.isCaptchaInventory(
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(event.getView().title()))) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= renderer.cells()) {
            // Клик по своему инвентарю, а не по сетке задачи.
            return;
        }

        // Ответ — номер ячейки, считая с единицы: так его сформировал сервис.
        String answer = String.valueOf(slot + 1);
        submit(player, challengeId, answer);
    }

    /** Проверяет ответ и реагирует на исход. */
    public void submit(@NotNull Player player, @NotNull String challengeId, @NotNull String answer) {
        UUID uuid = player.getUniqueId();

        core.captcha().verify(challengeId, answer).thenAccept(verdict ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (verdict.passed()) {
                        onPassed(player);
                        return;
                    }
                    if (verdict.exhausted() || verdict.challenge() == null) {
                        onExhausted(player);
                        return;
                    }
                    // Попытки остались: новая задача, а не та же самая. Повтор той же
                    // сетки позволил бы перебрать её кликами за несколько секунд.
                    active.remove(uuid);
                    player.closeInventory();
                    player.sendMessage(prefixed("Неверно. Осталось попыток: "
                            + verdict.remainingAttempts()));
                    playSound(player, core.config().getString(ConfigFile.PAPER,
                            "gui.failure-sound", ""));
                    issueAfterFailure(player);
                }));
    }

    private void issueAfterFailure(Player player) {
        core.authentication().findAccount(player.getName())
                .thenAccept(account -> Bukkit.getScheduler().runTask(plugin,
                        () -> issue(player, account.map(a -> a.id()).orElse(null))));
    }

    private void onPassed(Player player) {
        UUID uuid = player.getUniqueId();
        active.remove(uuid);
        player.closeInventory();
        player.sendMessage(prefixed("Проверка пройдена."));
        playSound(player, core.config().getString(ConfigFile.PAPER, "gui.success-sound", ""));
        passThrough(player);
    }

    /**
     * Переводит игрока к следующему шагу входа.
     *
     * <p>Состояние определяется наличием аккаунта: прошедший CAPTCHA новый игрок
     * идёт регистрироваться, существующий — вводить пароль.
     */
    private void passThrough(Player player) {
        UUID uuid = player.getUniqueId();
        core.authentication().findAccount(player.getName())
                .thenCompose(account -> core.sessions().setState(uuid,
                        account.isPresent() ? AuthState.AWAITING_LOGIN : AuthState.AWAITING_REGISTER))
                .exceptionally(e -> {
                    plugin.getLogger().severe("Не удалось перевести игрока " + player.getName()
                            + " после CAPTCHA: " + e);
                    return false;
                });
    }

    private void onExhausted(Player player) {
        active.remove(player.getUniqueId());
        player.closeInventory();

        String action = core.config().getString(ConfigFile.CAPTCHA, "on-failure", "kick");
        if ("new-challenge".equalsIgnoreCase(action)) {
            player.sendMessage(prefixed("Попытки исчерпаны. Выдана новая проверка."));
            issueAfterFailure(player);
            return;
        }
        core.sessions().setState(player.getUniqueId(), AuthState.BLOCKED);
        player.kick(miniMessage.deserialize(core.config().getString(ConfigFile.VELOCITY,
                "kick-messages.captcha-failed",
                "<red>Проверка не пройдена.")));
    }

    /**
     * Закрытие окна игроком.
     *
     * <p>Окно переоткрывается: незавершённая проверка не должна обходиться нажатием
     * Escape. Открытие делается следующим тиком — Bukkit не позволяет открыть
     * инвентарь внутри обработчика закрытия.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        String challengeId = active.get(player.getUniqueId());
        if (challengeId == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && active.containsKey(player.getUniqueId())) {
                reopen(player, challengeId);
            }
        });
    }

    /** Переоткрывает окно с той же задачей: попытка не считается израсходованной. */
    private void reopen(Player player, String challengeId) {
        core.captcha().findPending(player.getUniqueId()).thenAccept(found ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (found.isEmpty() || !player.isOnline()) {
                        active.remove(player.getUniqueId());
                        return;
                    }
                    active.remove(player.getUniqueId());
                    open(player, found.get());
                }));
    }

    /** Забывает игрока при отключении. */
    public void forget(@NotNull UUID uuid) {
        active.remove(uuid);
        issuing.remove(uuid);
    }

    private AuthContext contextOf(Player player) {
        IpAddress ip = IpAddress.ofNullable(player.getAddress() == null
                ? null
                : player.getAddress().getAddress().getHostAddress());
        return AuthContext.minecraft(ip, Bukkit.getServer().getName(),
                player.getProtocolVersion(), player.getClientBrandName());
    }

    private Component prefixed(String text) {
        String prefix = core.config().getString(ConfigFile.CONFIG, "messages.prefix", "");
        return miniMessage.deserialize(prefix + "<yellow>" + text);
    }

    private void playSound(Player player, String sound) {
        if (sound == null || sound.isBlank()) {
            return;
        }
        try {
            player.playSound(player.getLocation(),
                    org.bukkit.Sound.valueOf(sound.toUpperCase(java.util.Locale.ROOT)), 1f, 1f);
        } catch (IllegalArgumentException e) {
            // Неизвестное имя звука — не повод ломать выдачу задачи.
            plugin.getLogger().warning("Неизвестный звук в конфигурации: " + sound);
        }
    }
}
