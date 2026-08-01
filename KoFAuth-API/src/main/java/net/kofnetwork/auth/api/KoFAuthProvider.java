package net.kofnetwork.auth.api;

import net.kofnetwork.auth.api.exception.KoFAuthException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Статический доступ к {@link KoFAuth} для случаев, когда внедрить зависимость нельзя.
 *
 * <p><b>Когда пользоваться.</b> Только там, где экземпляр создаёт не наш код: команды
 * Bukkit, слушатели событий платформы, статические утилиты сторонних плагинов. Во всех
 * остальных местах экземпляр передаётся конструктором — так зависимость видна в
 * сигнатуре, а класс поддаётся тестированию без глобального состояния.
 *
 * <p>Класс существует именно потому, что Paper и Velocity сами инстанцируют часть
 * объектов и передать им зависимость невозможно. Это компромисс, а не рекомендуемый
 * способ доступа.
 */
public final class KoFAuthProvider {

    private static final AtomicReference<KoFAuth> INSTANCE = new AtomicReference<>();

    private KoFAuthProvider() {
        throw new AssertionError("Утилитный класс не подлежит созданию");
    }

    /**
     * Возвращает активный экземпляр.
     *
     * @throws KoFAuthException если KoFAuth ещё не запущен. Исключение, а не {@code null}:
     *                          обращение к службам до инициализации — ошибка порядка
     *                          загрузки плагинов, и она должна быть заметна сразу,
     *                          а не превратиться в {@code NullPointerException}
     *                          где-то в глубине обработчика
     */
    public static @NotNull KoFAuth get() {
        KoFAuth instance = INSTANCE.get();
        if (instance == null) {
            throw new KoFAuthException(
                    "KoFAuth ещё не инициализирован. Убедитесь, что плагин KoFAuth загружается "
                            + "раньше обращающегося к нему модуля (depend в plugin.yml).");
        }
        return instance;
    }

    /** Экземпляр или {@code null}, если система не запущена. */
    public static @Nullable KoFAuth getOrNull() {
        return INSTANCE.get();
    }

    /** Запущен ли KoFAuth. */
    public static boolean isAvailable() {
        return INSTANCE.get() != null;
    }

    /**
     * Регистрирует экземпляр. Вызывается только реализацией Core при старте.
     *
     * @throws IllegalStateException при попытке зарегистрировать второй экземпляр
     *                              поверх активного — это означает двойную загрузку
     *                              Core, при которой два независимых пула соединений
     *                              и две шины событий работали бы параллельно
     */
    public static void register(@NotNull KoFAuth instance) {
        if (!INSTANCE.compareAndSet(null, instance)) {
            throw new IllegalStateException(
                    "KoFAuth уже зарегистрирован. Повторная регистрация означает, что Core "
                            + "загружен дважды.");
        }
    }

    /** Снимает регистрацию при остановке. */
    public static void unregister() {
        INSTANCE.set(null);
    }
}
