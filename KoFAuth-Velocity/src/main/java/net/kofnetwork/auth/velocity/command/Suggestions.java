package net.kofnetwork.auth.velocity.command;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Разбор аргументов для автодополнения команд прокси.
 *
 * <p><b>Зачем отдельный класс.</b> Velocity передаёт в {@code suggest} результат
 * разбиения строки с сохранением пустых частей: после {@code /auth player } массив
 * равен {@code ["player", ""]}, а не {@code ["player"]}. Команды это игнорировали и
 * проверяли только {@code args.length <= 1}, поэтому дополнялось лишь первое слово:
 * на {@code /auth player <TAB>} подсказок не было вовсе, хотя именно там нужен
 * список ников. Здесь эта разница спрятана за понятиями «глубина» и «частичное
 * слово», одинаковыми для всех команд.
 *
 * <p>Все методы возвращают уже отфильтрованный по началу слова список: возвращать
 * полный набор и полагаться на фильтрацию клиентом нельзя — на 1.21 клиент
 * показывает то, что прислал сервер, без отбора.
 */
final class Suggestions {

    private Suggestions() {
        throw new AssertionError("Утилитный класс не подлежит созданию");
    }

    /**
     * Номер дополняемого аргумента, считая с единицы.
     *
     * <p>{@code /auth} → 1 (дополняется подкоманда), {@code /auth pl} → 1,
     * {@code /auth player } → 2, {@code /auth player Ste} → 2.
     */
    static int depth(String @NotNull [] args) {
        return args.length == 0 ? 1 : args.length;
    }

    /**
     * Часть слова, которую игрок уже набрал.
     *
     * <p>Пустая строка, если он только что поставил пробел.
     */
    static @NotNull String partial(String @NotNull [] args) {
        return args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    }

    /** Первый аргумент в нижнем регистре — обычно подкоманда. */
    static @NotNull String subcommand(String @NotNull [] args) {
        return args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
    }

    /** Оставляет варианты, начинающиеся с набранного. */
    static @NotNull List<String> matching(@NotNull Collection<String> candidates,
                                          @NotNull String partial) {
        return candidates.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(partial))
                .sorted()
                .toList();
    }

    /**
     * Ники игроков сети.
     *
     * <p>Берутся подключённые к прокси, а не все аккаунты из базы: выборка по
     * таблице на каждое нажатие TAB — это запрос в MySQL на каждый символ, а
     * администратору почти всегда нужен тот, кто сейчас в сети. Ник, которого нет
     * в списке, по-прежнему можно ввести целиком.
     */
    static @NotNull List<String> onlinePlayers(@NotNull ProxyServer proxy,
                                               @NotNull String partial) {
        return matching(proxy.getAllPlayers().stream().map(Player::getUsername).toList(), partial);
    }
}
