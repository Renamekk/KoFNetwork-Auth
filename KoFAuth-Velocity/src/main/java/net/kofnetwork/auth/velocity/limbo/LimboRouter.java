package net.kofnetwork.auth.velocity.limbo;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kofnetwork.auth.api.config.ConfigFile;
import net.kofnetwork.auth.api.config.ConfigurationService;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Выбор Limbo- и лобби-сервера.
 *
 * <p>Стратегия задаётся конфигурацией. По умолчанию {@code least-players}: игрок
 * попадает на наименее загруженный инстанс, что при массовом заходе (или при атаке
 * ботов) распределяет нагрузку вместо того, чтобы утопить первый в списке.
 *
 * <p><b>Считается загрузка Velocity, а не heartbeat из базы.</b> Прокси знает
 * фактическое число подключённых к каждому серверу игроков и знает, живо ли
 * соединение, — это точнее и не требует обращения к MySQL в горячем пути.
 */
public final class LimboRouter {

    private final ProxyServer proxy;
    private final ConfigurationService config;
    private final Logger logger;

    public LimboRouter(@NotNull ProxyServer proxy,
                       @NotNull ConfigurationService config,
                       @NotNull Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.logger = logger;
    }

    /** Доступный Limbo-сервер. */
    public @NotNull Optional<RegisteredServer> selectLimbo() {
        return select(config.getStringList(ConfigFile.VELOCITY, "limbo.servers"),
                config.getString(ConfigFile.VELOCITY, "limbo.strategy", "least-players"),
                "limbo");
    }

    /** Доступный сервер лобби. */
    public @NotNull Optional<RegisteredServer> selectLobby() {
        return select(config.getStringList(ConfigFile.VELOCITY, "lobby.servers"),
                config.getString(ConfigFile.VELOCITY, "lobby.strategy", "least-players"),
                "lobby");
    }

    private Optional<RegisteredServer> select(List<String> names, String strategy, String kind) {
        if (names.isEmpty()) {
            logger.error("В velocity.yml не указано ни одного сервера типа {}", kind);
            return Optional.empty();
        }

        List<RegisteredServer> candidates = names.stream()
                .map(proxy::getServer)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (candidates.isEmpty()) {
            logger.error("Ни один из серверов {} не зарегистрирован в velocity.toml: {}",
                    kind, names);
            return Optional.empty();
        }

        return switch (strategy.toLowerCase(Locale.ROOT)) {
            case "random" -> Optional.of(
                    candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
            case "first-available" -> Optional.of(candidates.get(0));
            default -> candidates.stream()
                    .min(Comparator.comparingInt(server -> server.getPlayersConnected().size()));
        };
    }

    /** Является ли сервер Limbo-сервером. */
    public boolean isLimbo(@NotNull String serverName) {
        return config.getStringList(ConfigFile.VELOCITY, "limbo.servers").stream()
                .anyMatch(name -> name.equalsIgnoreCase(serverName));
    }

    /**
     * Что делать, если Limbo недоступен.
     *
     * @return {@code true}, если игрока следует отключить
     */
    public boolean kickWhenLimboUnavailable() {
        // allow-lobby пропустил бы неаутентифицированного игрока на боевой сервер.
        // Значение по умолчанию — единственное безопасное.
        return !"allow-lobby".equalsIgnoreCase(
                config.getString(ConfigFile.VELOCITY, "limbo.on-unavailable", "kick"));
    }
}
