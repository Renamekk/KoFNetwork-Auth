package net.kofnetwork.auth.paper.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Закрывает сервер, когда аутентификация не работает.
 *
 * <p><b>Зачем это понадобилось.</b> Прежде отказ запуска приводил к
 * {@code disablePlugin(this)} — и на этом всё. Плагин выключался, а сервер оставался
 * работать: слушал порт, принимал подключения и никак их не проверял. Прямое
 * подключение в обход прокси попадало на живой игровой мир под любым ником.
 * Выключенный плагин защиты не защищает.
 *
 * <p>Этот слушатель регистрируется <em>вместо</em> плагина и отвергает всё:
 * подключения — на самом раннем этапе, до создания игрового профиля, уже
 * подключённых — киком. Он не зависит ни от Core, ни от конфигурации, потому что
 * именно их отсутствие его и включает.
 *
 * <p>Регистрация через {@link Plugin} самого KoFAuth невозможна: выключенный плагин
 * не может держать слушателей. Поэтому владельцем выступает переданный извне
 * плагин-держатель — на практике сам KoFAuth <em>до</em> выключения, а решение о
 * полной остановке сервера принимает вызывающий.
 */
public final class LockdownListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Component reason;

    public LockdownListener(@NotNull String reason) {
        this.reason = MINI.deserialize(reason);
    }

    /**
     * Отсечка до создания игрового профиля.
     *
     * <p>Самая ранняя доступная точка: соединение ещё не стало игроком, и ни один
     * плагин не успел выдать ему прав, инвентарь или место в мире.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, reason);
    }

    /**
     * Страховка на случай, если подключение всё же дошло сюда.
     *
     * <p>Порядок обработчиков задаёт Paper, и полагаться на то, что предыдущая
     * отсечка сработала у всех сборок и версий, не стоит.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().kick(reason);
    }

    /** Отключает всех, кто уже на сервере. */
    public void kickEveryone(@NotNull Iterable<? extends Player> players) {
        for (Player player : players) {
            player.kick(reason);
        }
    }
}
