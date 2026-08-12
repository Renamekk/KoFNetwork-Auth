package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.core.KoFAuthCore;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кто допущен к административным командам и насколько.
 *
 * <p>Источников доступа четыре, и они намеренно неравноценны.
 *
 * <p><b>Безусловный полный доступ</b> — консоль, OP игрового сервера, перечень
 * {@code velocity.yml → admin.operators} и право {@code kofauth.admin} стороннего
 * плагина прав. Каждый из них означает «этот человек уже управляет сервером»:
 * тому, кто может выдать себе OP, отказывать в просмотре чужого IP бессмысленно —
 * он и так может прочитать базу. Поэтому здесь права выдаются целиком, без
 * разбора узлов.
 *
 * <p><b>Доступ по узлу</b> — роли KoFAuth из базы. Они общие для всей сети,
 * меняются из панели и позволяют выдать модератору ровно {@code /auth player},
 * не открывая {@code /auth export}.
 *
 * <p><b>Почему OP нельзя спросить у Velocity.</b> У прокси нет своей системы прав:
 * без стороннего плагина {@code hasPermission} отвечает {@code UNDEFINED}, что
 * приводится к {@code false} для любого игрока. Сам {@code ops.json} лежит на
 * игровом сервере — другом процессе, нередко и другой машине. Признак переносит
 * плагин Paper через общее хранилище состояния, см.
 * {@link net.kofnetwork.auth.core.admin.OperatorRegistry}.
 *
 * <p><b>Кэш нужен из-за TAB.</b> Velocity спрашивает {@code hasPermission}
 * синхронно, в том числе на каждое нажатие клавиши автодополнения. Ходить оттуда
 * в базу и в Redis нельзя, поэтому ответ запоминается, а обновляется он в фоне.
 */
public final class AdminAccess {

    /** Узел, дающий полный доступ у стороннего плагина прав. */
    public static final String ROOT_PERMISSION = "kofauth.admin";

    private final KoFAuthCore core;
    private final Logger logger;

    /**
     * Что уже известно про игрока.
     *
     * <p>Отражение ответов хранилища и базы, а не источник истины. Живёт до
     * отключения игрока или до {@code /auth reload}: выданная только что роль
     * должна подхватываться перезагрузкой, а не переподключением.
     */
    private final Map<UUID, Boolean> fullAccess = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> anyAccess = new ConcurrentHashMap<>();

    public AdminAccess(@NotNull KoFAuthCore core, @NotNull Logger logger) {
        this.core = core;
        this.logger = logger;
    }

    /**
     * Допускать ли источник к действию с этим узлом прав.
     *
     * <p>Полный доступ проверяется первым: он не требует ни базы, ни аккаунта
     * KoFAuth, а администратор сервера может не иметь игрового аккаунта вовсе.
     */
    public @NotNull CompletableFuture<Boolean> allows(@NotNull CommandSource source,
                                                      @NotNull String node) {
        if (!(source instanceof Player player)) {
            // Консоль имеет все права безусловно: у неё нет аккаунта, и заводить
            // его администратору сервера бессмысленно.
            return CompletableFuture.completedFuture(true);
        }
        return hasFullAccess(player).thenCompose(full -> full
                ? CompletableFuture.completedFuture(true)
                : hasRolePermission(player, node));
    }

    /**
     * Имеет ли игрок полный доступ ко всем командам.
     *
     * <p>Ответ запоминается, чтобы синхронная проверка Velocity могла им
     * воспользоваться.
     */
    public @NotNull CompletableFuture<Boolean> hasFullAccess(@NotNull Player player) {
        if (player.hasPermission(ROOT_PERMISSION) || player.hasPermission("*")) {
            fullAccess.put(player.getUniqueId(), true);
            return CompletableFuture.completedFuture(true);
        }
        if (isListedOperator(player)) {
            fullAccess.put(player.getUniqueId(), true);
            return CompletableFuture.completedFuture(true);
        }
        return core.operators().isOperator(player.getUniqueId())
                .thenApply(op -> {
                    fullAccess.put(player.getUniqueId(), op);
                    return op;
                })
                .exceptionally(e -> {
                    // Хранилище не ответило. Считать игрока оператором в этот
                    // момент нельзя: отказ Redis не повод раздавать полный доступ.
                    logger.warn("Не удалось проверить OP игрока {}", player.getUsername(), e);
                    return false;
                });
    }

    /**
     * Перечислен ли игрок в {@code velocity.yml → admin.operators}.
     *
     * <p>Запасной путь для сетей без общего Redis: там отметка OP с бэкенда до
     * прокси не доходит, и должен остаться способ назвать администраторов прямо.
     * Принимаются и ники, и UUID — ник удобнее набирать, UUID переживает смену ника.
     */
    private boolean isListedOperator(Player player) {
        List<String> listed = core.config().getStringList(ConfigFile.VELOCITY, "admin.operators");
        if (listed.isEmpty()) {
            return false;
        }
        String username = player.getUsername().toLowerCase(Locale.ROOT);
        String uuid = player.getUniqueId().toString();
        return listed.stream()
                .map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .anyMatch(entry -> entry.equals(username) || entry.equals(uuid));
    }

    /** Право из ролей KoFAuth, привязанных к игровому аккаунту. */
    private CompletableFuture<Boolean> hasRolePermission(Player player, String node) {
        return core.authentication().findAccount(player.getUsername())
                .thenCompose(account -> account
                        .map(value -> core.adminOperations().hasPermission(value.id(), node))
                        .orElseGet(() -> CompletableFuture.completedFuture(false)))
                .exceptionally(e -> false);
    }

    // ------------------------------------------------------------------ синхронный путь

    /**
     * Пускать ли игрока к команде вообще — ответ для {@link
     * com.velocitypowered.api.command.SimpleCommand#hasPermission}.
     *
     * <p>Пока ответ неизвестен, команда пропускается: точная проверка всё равно
     * выполняется при исполнении и откажет с понятным сообщением. Пропуск здесь
     * ничего не открывает — он лишь позволяет первому вызову дойти до настоящей
     * проверки, а не потеряться.
     */
    public boolean maySee(@NotNull CommandSource source) {
        if (!(source instanceof Player player)) {
            return true;
        }
        if (player.hasPermission(ROOT_PERMISSION) || player.hasPermission("*")
                || isListedOperator(player)) {
            return true;
        }
        Boolean known = anyAccess.get(player.getUniqueId());
        if (known != null) {
            return known;
        }
        refresh(player);
        return true;
    }

    /**
     * Есть ли у игрока полный доступ по уже известному ответу.
     *
     * <p>Используется только для оформления вывода — пометки «полный доступ» в
     * справке. Решения о допуске принимаются по {@link #allows}, который
     * спрашивает хранилище, а не отражение.
     */
    public boolean hasFullAccessCached(@NotNull CommandSource source) {
        if (!(source instanceof Player player)) {
            return true;
        }
        return Boolean.TRUE.equals(fullAccess.get(player.getUniqueId()));
    }

    /** Спрашивает хранилище и базу, запоминая ответы. */
    private void refresh(Player player) {
        allows(player, ROOT_PERMISSION)
                .thenAccept(allowed -> anyAccess.put(player.getUniqueId(), allowed))
                .exceptionally(e -> {
                    logger.warn("Не удалось проверить права игрока {}", player.getUsername(), e);
                    return null;
                });
    }

    /**
     * Забывает игрока при отключении.
     *
     * <p>Иначе карта растёт на каждого зашедшего, а выданная роль не подхватится
     * до перезапуска прокси: вернувшийся игрок получит ответ, записанный
     * в прошлое подключение.
     */
    public void forget(@NotNull UUID playerUuid) {
        fullAccess.remove(playerUuid);
        anyAccess.remove(playerUuid);
    }

    /** Сбрасывает все запомненные ответы — вызывается из {@code /auth reload}. */
    public void invalidate() {
        fullAccess.clear();
        anyAccess.clear();
    }

    /**
     * Доходит ли признак OP с игровых серверов до прокси.
     *
     * <p>Показывается в {@code /auth info}: молча не работающий подъём прав по OP
     * выглядит как поломка плагина, а не как выключенный общий кэш.
     */
    public boolean operatorsShared() {
        return core.operators().isShared();
    }
}
