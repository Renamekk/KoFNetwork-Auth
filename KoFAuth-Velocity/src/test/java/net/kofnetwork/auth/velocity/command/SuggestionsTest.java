package net.kofnetwork.auth.velocity.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор аргументов автодополнения.
 *
 * <p>Velocity сохраняет пустой элемент после завершающего пробела, и именно его
 * игнорировали команды: {@code /auth player <TAB>} приходил как
 * {@code ["player", ""]}, проверка {@code args.length <= 1} не срабатывала,
 * и подсказок не было ни одной.
 */
class SuggestionsTest {

    @Nested
    @DisplayName("Глубина аргумента")
    class Depth {

        @Test
        @DisplayName("пустая строка — дополняется первое слово")
        void emptyLine() {
            assertThat(Suggestions.depth(new String[0])).isEqualTo(1);
        }

        @Test
        @DisplayName("начатое первое слово — по-прежнему первое")
        void partialFirstWord() {
            assertThat(Suggestions.depth(new String[]{"pla"})).isEqualTo(1);
        }

        /** Регрессия: раньше этот случай считался первым словом. */
        @Test
        @DisplayName("пробел после подкоманды — дополняется второе слово")
        void spaceAfterSubcommand() {
            assertThat(Suggestions.depth(new String[]{"player", ""})).isEqualTo(2);
        }

        @Test
        @DisplayName("начатое второе слово — второе")
        void partialSecondWord() {
            assertThat(Suggestions.depth(new String[]{"player", "Ste"})).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Набранная часть слова")
    class Partial {

        @Test
        @DisplayName("пустая, пока ничего не набрано")
        void emptyWhenNothingTyped() {
            assertThat(Suggestions.partial(new String[]{"player", ""})).isEmpty();
        }

        @Test
        @DisplayName("берётся последнее слово, а не первое")
        void takesLastWord() {
            assertThat(Suggestions.partial(new String[]{"player", "Ste"})).isEqualTo("ste");
        }

        @Test
        @DisplayName("приводится к нижнему регистру для сравнения")
        void lowercased() {
            assertThat(Suggestions.partial(new String[]{"RESET"})).isEqualTo("reset");
        }
    }

    @Nested
    @DisplayName("Отбор вариантов")
    class Matching {

        @Test
        @DisplayName("пустой ввод оставляет все варианты")
        void emptyPartialKeepsEverything() {
            assertThat(Suggestions.matching(List.of("lock", "unlock", "logs"), ""))
                    .containsExactly("lock", "logs", "unlock");
        }

        @Test
        @DisplayName("отбирает по началу слова, а не по вхождению")
        void matchesPrefixOnly() {
            assertThat(Suggestions.matching(List.of("lock", "unlock", "logs"), "lo"))
                    .containsExactly("lock", "logs");
        }

        /**
         * Отбор обязателен на стороне сервера: клиент показывает присланное как
         * есть и сам ничего не отфильтровывает.
         */
        @Test
        @DisplayName("регистр набранного не мешает совпадению")
        void caseInsensitive() {
            assertThat(Suggestions.matching(List.of("Steve", "steward"), "ste"))
                    .containsExactly("Steve", "steward");
        }

        @Test
        @DisplayName("несовпадение даёт пустой список, а не весь набор")
        void noMatches() {
            assertThat(Suggestions.matching(List.of("lock", "logs"), "zzz")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Имя подкоманды")
    class Subcommand {

        @Test
        @DisplayName("пустое на пустой строке")
        void emptyLine() {
            assertThat(Suggestions.subcommand(new String[0])).isEmpty();
        }

        @Test
        @DisplayName("берётся первое слово независимо от глубины")
        void firstWordRegardlessOfDepth() {
            assertThat(Suggestions.subcommand(new String[]{"ResetPassword", "Steve", ""}))
                    .isEqualTo("resetpassword");
        }
    }
}
