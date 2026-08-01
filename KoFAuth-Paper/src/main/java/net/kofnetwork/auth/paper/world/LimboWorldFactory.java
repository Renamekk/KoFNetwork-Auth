package net.kofnetwork.auth.paper.world;

import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.Random;
import java.util.logging.Logger;

/**
 * Создание мира Limbo.
 *
 * <p>Мир генерируется пустым: земля, деревья и мобы игроку до входа не нужны, а
 * генерация обычного мира — это дисковое пространство и время загрузки чанков на
 * ровном месте. Пустой генератор отдаёт чанки мгновенно и не занимает места.
 *
 * <p>Правила мира выставляются так, чтобы в Limbo вообще ничего не происходило:
 * не менялось время, не шёл дождь, не спавнились мобы, не тикали блоки. Это не
 * только вопрос порядка — это отсутствие любой нагрузки на сервер, который при
 * атаке ботов принимает тысячи подключений в минуту.
 */
public final class LimboWorldFactory {

    /** Генератор, отдающий пустые чанки. */
    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }

        @Override
        public @NotNull Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
            return new Location(world, 0.5, 100, 0.5);
        }
    }

    private LimboWorldFactory() {
        throw new AssertionError("Утилитный класс не подлежит созданию");
    }

    /**
     * Загружает мир Limbo, создавая его при отсутствии.
     *
     * @param name       имя мира
     * @param fixedTime  фиксированное время суток
     * @param noWeather  отключить погоду
     */
    public static @NotNull World loadOrCreate(@NotNull String name,
                                              long fixedTime,
                                              boolean noWeather,
                                              @NotNull Logger logger) {
        World existing = org.bukkit.Bukkit.getWorld(name);
        if (existing != null) {
            applyRules(existing, fixedTime, noWeather);
            return existing;
        }

        logger.info("Создание мира Limbo '" + name + "'...");
        World world = new WorldCreator(name)
                .generator(new VoidGenerator())
                .type(WorldType.FLAT)
                .generateStructures(false)
                .createWorld();

        if (world == null) {
            throw new IllegalStateException("Не удалось создать мир Limbo '" + name + "'");
        }
        applyRules(world, fixedTime, noWeather);
        logger.info("Мир Limbo '" + name + "' готов");
        return world;
    }

    /** Замораживает мир: ничего не растёт, не тикает и не спавнится. */
    private static void applyRules(World world, long fixedTime, boolean noWeather) {
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(fixedTime);
        world.setStorm(false);
        world.setThundering(false);

        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.DO_ENTITY_DROPS, false);
        world.setGameRule(GameRule.DO_TILE_DROPS, false);
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setGameRule(GameRule.FIRE_DAMAGE, false);
        world.setGameRule(GameRule.DROWNING_DAMAGE, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        // Спавн-чанки Limbo держать в памяти не нужно: игроки в нём не строят,
        // а на крупной сети это десятки мегабайт впустую.
        world.setGameRule(GameRule.SPAWN_RADIUS, 0);

        if (noWeather) {
            world.setWeatherDuration(Integer.MAX_VALUE);
        }

        world.setAutoSave(false);
        world.setKeepSpawnInMemory(false);
    }
}
