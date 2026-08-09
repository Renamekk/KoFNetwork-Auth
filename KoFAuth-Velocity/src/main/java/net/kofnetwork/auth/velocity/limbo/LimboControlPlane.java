package net.kofnetwork.auth.velocity.limbo;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Управление заранее подготовленными Limbo-инстансами.
 *
 * <p><b>Почему это интерфейс, а не вызов Docker.</b> Прокси стоит на границе сети и
 * первым принимает соединения от кого угодно. Дать ему сокет Docker значит дать право
 * запускать произвольный контейнер на хосте — то есть свести любую уязвимость прокси к
 * захвату машины целиком. Здесь прокси лишь просит внешнюю службу включить или выключить
 * инстанс <em>из заранее объявленного перечня</em>; чем именно она это делает — Docker,
 * systemd, Kubernetes или человек по регламенту — прокси не знает и знать не должен.
 *
 * <p>Инстансы не создаются: {@link #start(String)} включает уже подготовленный сервер,
 * известный и {@code velocity.toml}, и control-plane. Просьба включить незнакомое имя
 * отвергается на стороне службы.
 */
public interface LimboControlPlane {

    /** Управляется ли жизненный цикл вообще. */
    boolean isEnabled();

    /** Инстансы, которыми служба готова управлять. */
    @NotNull CompletableFuture<List<InstanceState>> list();

    /**
     * Включает подготовленный инстанс.
     *
     * @return {@code true}, если служба приняла запрос; готовность подтверждает ping
     */
    @NotNull CompletableFuture<Boolean> start(@NotNull String instance);

    /**
     * Выключает инстанс.
     *
     * <p>Вызывается только после того, как инстанс опустел и выведен из маршрутизации.
     */
    @NotNull CompletableFuture<Boolean> stop(@NotNull String instance);

    /**
     * @param running запущен ли процесс
     * @param ready   принимает ли он соединения
     */
    record InstanceState(@NotNull String name, boolean running, boolean ready) {
    }

    /**
     * Заглушка: жизненным циклом никто не управляет.
     *
     * <p>Подставляется, когда служба не настроена. Все перечисленные Limbo считаются
     * поднятыми снаружи, маршрутизация продолжает работать по здоровью и ёмкости, а
     * попытки включить или выключить инстанс не делаются вовсе — молча «успешный»
     * запуск ввёл бы систему в заблуждение насчёт доступной ёмкости.
     */
    static @NotNull LimboControlPlane disabled() {
        return new LimboControlPlane() {

            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public @NotNull CompletableFuture<List<InstanceState>> list() {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public @NotNull CompletableFuture<Boolean> start(@NotNull String instance) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public @NotNull CompletableFuture<Boolean> stop(@NotNull String instance) {
                return CompletableFuture.completedFuture(false);
            }
        };
    }
}
