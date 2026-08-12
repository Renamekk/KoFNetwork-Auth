package net.kofnetwork.auth.core.admin;

import net.kofnetwork.auth.core.cache.CacheProvider;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Кто из игроков сети имеет OP на игровом сервере.
 *
 * <p><b>Зачем это вообще нужно.</b> Администратор ожидает, что выданный им самому
 * себе OP работает везде, включая {@code /auth}. Но OP — понятие Bukkit: он живёт
 * в {@code ops.json} игрового сервера. Velocity собственной системы прав не имеет
 * вовсе и без стороннего плагина отвечает {@code UNDEFINED} на любой запрос,
 * а файла соседнего процесса (нередко и соседней машины) не видит. Поэтому признак
 * переносится через то единственное, что у прокси и бэкенда общее, — хранилище.
 *
 * <p>Отметку ставит плагин Paper при входе игрока и обновляет по таймеру; прокси
 * её только читает. Срок жизни выбран заведомо больше периода обновления: если
 * бэкенд выключился, отметка исчезает сама, и снятый OP не остаётся действовать
 * бесконечно.
 *
 * <p><b>Ограничение.</b> Признак пересекает границу процесса только через общее
 * хранилище. При {@code cache.enabled: false} каждый процесс держит свою память,
 * и прокси отметки бэкенда не увидит — там роль источника истины играют
 * {@code velocity.yml → admin.operators} и права стороннего плагина.
 *
 * <p>Отказ хранилища трактуется как «не оператор»: подниматься до полного доступа
 * по недоступности Redis система не должна.
 */
public final class OperatorRegistry {

    /**
     * Сколько живёт отметка без обновления.
     *
     * <p>Больше периода обновления с запасом на паузу сборщика мусора и на
     * секундную недоступность хранилища, но достаточно мало, чтобы снятый OP
     * перестал действовать в пределах минут, а не часов.
     */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final CacheProvider cache;

    public OperatorRegistry(@NotNull CacheProvider cache) {
        this.cache = cache;
    }

    /** Отмечает игрока оператором на {@link #DEFAULT_TTL}. */
    public @NotNull CompletableFuture<Void> mark(@NotNull UUID playerUuid) {
        return mark(playerUuid, DEFAULT_TTL);
    }

    /** Отмечает игрока оператором на указанный срок. */
    public @NotNull CompletableFuture<Void> mark(@NotNull UUID playerUuid, @NotNull Duration ttl) {
        return cache.set(key(playerUuid), "1", ttl);
    }

    /**
     * Снимает отметку.
     *
     * <p>Вызывается при выходе игрока и при снятии OP. Без этого снятый OP
     * действовал бы до истечения срока — недолго, но и этого хватает, чтобы
     * снятие выглядело не сработавшим.
     */
    public @NotNull CompletableFuture<Void> clear(@NotNull UUID playerUuid) {
        return cache.delete(key(playerUuid)).thenApply(ignored -> null);
    }

    /**
     * Оператор ли игрок.
     *
     * <p>Отказ хранилища даёт {@code false}: отсутствие ответа не должно
     * превращаться в выдачу полного доступа.
     */
    public @NotNull CompletableFuture<Boolean> isOperator(@NotNull UUID playerUuid) {
        return cache.exists(key(playerUuid)).exceptionally(e -> false);
    }

    /**
     * Переносится ли отметка между процессами при текущей конфигурации.
     *
     * <p>Прокси показывает это в {@code /auth info}: молча не работающий признак
     * OP выглядит как «плагин сломался», а не как «выключен общий кэш».
     */
    public boolean isShared() {
        return cache.isAvailable() && cache.isDistributed();
    }

    private static String key(UUID playerUuid) {
        return CacheProvider.Keys.of(CacheProvider.Keys.OPERATOR, playerUuid);
    }
}
