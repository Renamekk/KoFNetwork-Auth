package net.kofnetwork.auth.core.cache;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Заглушка кэша: чтения всегда промахиваются, записи игнорируются.
 *
 * <p>Подставляется, когда Redis выключен в конфигурации или не поднялся. Смысл в
 * том, чтобы отсутствие кэша не требовало условий {@code if (cache != null)} в
 * каждом вызывающем месте: вызывающий работает с одним и тем же интерфейсом, а
 * промах кэша он и так обязан обрабатывать походом в MySQL.
 *
 * <p><b>Что при этом теряется.</b> Сессии перестают быть общими между процессами:
 * игрок, вошедший через один прокси, для второго прокси не аутентифицирован.
 * События не доставляются между процессами — смена пароля на сайте не разорвёт
 * игровую сессию. Ограничение скорости считается только в пределах одного процесса.
 * На сети из одного прокси и одного сервера всё это приемлемо; на нескольких — нет.
 *
 * <p>{@link #setIfAbsent} возвращает {@code true}: без общего хранилища
 * распределённая блокировка невозможна, и единственный честный ответ —
 * «блокировка получена», потому что конкурентов в пределах процесса нет.
 * Вызывающий, которому нужна настоящая атомарность между узлами, обязан
 * проверять {@link #isAvailable()}.
 *
 * <p><b>Класс не {@code final} намеренно.</b> Он служит базой для частичных
 * реализаций: тестовому двойнику или узкоспециализированному провайдеру достаточно
 * переопределить две-три операции, а не двадцать. Это прямое продолжение роли
 * Null Object — «ничего не делать» как разумное поведение по умолчанию.
 */
public class NoopCacheProvider implements CacheProvider {

    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    @Override
    public @NotNull CompletableFuture<Optional<String>> get(@NotNull String key) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public @NotNull CompletableFuture<Void> set(@NotNull String key,
                                                @NotNull String value,
                                                @NotNull Duration ttl) {
        return DONE;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> setIfAbsent(@NotNull String key,
                                                            @NotNull String value,
                                                            @NotNull Duration ttl) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull String key) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> getAndDelete(@NotNull String key) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public @NotNull CompletableFuture<Boolean> exists(@NotNull String key) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public @NotNull CompletableFuture<Map<String, String>> getHash(@NotNull String key) {
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public @NotNull CompletableFuture<Void> setHash(@NotNull String key,
                                                    @NotNull Map<String, String> values,
                                                    @NotNull Duration ttl) {
        return DONE;
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> getHashField(@NotNull String key,
                                                                      @NotNull String field) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Всегда возвращает 1.
     *
     * <p>Не 0: счётчик, который вечно показывает ноль, означал бы, что ограничение
     * скорости выключено. Единица честнее — «одно событие, лимит не исчерпан», —
     * и при этом ограничение просто не накапливается между вызовами.
     */
    @Override
    public @NotNull CompletableFuture<Long> increment(@NotNull String key,
                                                       @NotNull Duration ttlOnCreate) {
        return CompletableFuture.completedFuture(1L);
    }

    @Override
    public @NotNull CompletableFuture<Long> incrementSlidingWindow(@NotNull String key,
                                                                    @NotNull Duration window) {
        return CompletableFuture.completedFuture(1L);
    }

    @Override
    public @NotNull CompletableFuture<Long> countSlidingWindow(@NotNull String key,
                                                                @NotNull Duration window) {
        return CompletableFuture.completedFuture(0L);
    }

    @Override
    public @NotNull CompletableFuture<Long> deleteByPattern(@NotNull String pattern) {
        return CompletableFuture.completedFuture(0L);
    }

    @Override
    public @NotNull CompletableFuture<Long> publish(@NotNull String channel, @NotNull String message) {
        return CompletableFuture.completedFuture(0L);
    }

    @Override
    public @NotNull Subscription subscribe(@NotNull String channel, @NotNull Consumer<String> handler) {
        // Подписка на канал, в который никто не пишет: закрывать нечего.
        return () -> {
        };
    }

    @Override
    public @NotNull CompletableFuture<List<String>> keys(@NotNull String pattern) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public @NotNull String providerName() {
        return "none";
    }

    @Override
    public void close() {
        // Нечего освобождать.
    }
}
