"""Шрифты карточек.

Шрифт — общий ресурс, а не часть кода: он лежит рядом с шаблонами и берётся
оттуда обоими ботами. Пиксельное начертание здесь не украшение — им набран
логотип сети, и подпись под ним обычным гротеском выглядит как чужая.

Начертания берутся из :data:`FAMILY` по порядку. Первое найденное побеждает,
поэтому развёртывание может подменить шрифт своим, положив файл в каталог
ресурсов, — и не может остаться совсем без шрифта: если не нашлось ни одного
файла, карточки просто выключаются, а боты продолжают работать текстом.

Кириллица проверяется, а не предполагается. Пиксельные шрифты сплошь и рядом
покрывают только латиницу, и подставленный «красивый» файл без кириллицы
превратил бы «Профиль» в набор пустых квадратов — молча, потому что никакой
ошибки при этом не возникает.
"""

from __future__ import annotations

import logging
import os
from typing import Final

from PIL import ImageFont

LOGGER = logging.getLogger(__name__)

FontFile = ImageFont.FreeTypeFont

#: Начертания. Обычное несёт основной текст, полужирное — подписи и значения,
#: чёрное — заголовок экрана. Больше трёх не нужно: карточка это четыре строки,
#: а не журнальная полоса.
REGULAR: Final = "regular"
BOLD: Final = "bold"
BLACK: Final = "black"

#: Имена файлов по начертаниям — в порядке предпочтения.
FAMILY: Final[dict[str, tuple[str, ...]]] = {
    REGULAR: ("Monocraft.ttf", "Monocraft-Regular.ttf"),
    BOLD: ("Monocraft-Bold.ttf", "Monocraft-SemiBold.ttf", "Monocraft.ttf"),
    BLACK: ("Monocraft-Black.ttf", "Monocraft-Bold.ttf", "Monocraft.ttf"),
}

#: На этих символах проверяется пригодность файла. Кириллица и цифры — всё,
#: что действительно встречается в карточке; латиница берётся заодно, потому
#: что ники бывают любыми.
PROBE: Final = "ЖщёЙ0123Wg"

#: Символ, которым заменяется всё, чего в шрифте нет. Пустой квадрат вместо
#: буквы читается как поломка карточки, а точка — как незнакомый символ.
UNKNOWN: Final = "·"

#: Заведомо отсутствующий символ: область частного использования, осмысленного
#: глифа там не бывает. По нему опознаётся ``.notdef`` — квадратик, которым PIL
#: молча заменяет всё, чего в шрифте нет.
ABSENT: Final = ""


class FontsUnavailable(RuntimeError):
    """Ни одного пригодного файла шрифта. Карточки не рисуются."""


class FontBook:
    """Открытые начертания одного каталога ресурсов.

    ``ImageFont.truetype`` каждый раз заново читает и разбирает файл, а
    карточка просит шрифт по десятку раз за отрисовку и десятки раз в секунду
    под нагрузкой. Поэтому открытые кегли складываются в словарь.

    Словарь не растёт бесконечно: кегли берутся из :mod:`.theme` и ряда
    ужимания, то есть их конечное и небольшое число — несколько десятков на
    все экраны вместе.
    """

    __slots__ = ("_directory", "_files", "_opened", "_missing", "_known")

    def __init__(self, directory: str) -> None:
        self._directory = directory
        self._files: dict[str, str] = {}
        self._opened: dict[tuple[str, int], FontFile] = {}
        #: Символы, о нехватке которых уже сообщили. Без этого журнал
        #: заполняется одной и той же строкой на каждой отрисовке.
        self._missing: set[str] = set()
        #: Ответы «есть ли такой символ»: проверка рисует растр, а ники
        #: повторяются — один и тот же человек открывает профиль десятки раз.
        self._known: dict[str, bool] = {}
        self._resolve()

    # ------------------------------------------------------------------ поиск

    def _resolve(self) -> None:
        for weight, names in FAMILY.items():
            for name in names:
                path = os.path.join(self._directory, name)
                if not os.path.isfile(path):
                    continue
                if not self._usable(path):
                    LOGGER.warning("Шрифт %s пропущен: в нём нет кириллицы", name)
                    continue
                self._files[weight] = path
                break

        if not self._files:
            # Без единого файла рисовать нечем, и притворяться, что карточка
            # получится, незачем: бот останется на текстовом интерфейсе.
            raise FontsUnavailable(
                f"В каталоге {self._directory!r} нет пригодных шрифтов"
            )

        # Недостающие начертания подменяются любым найденным: одна толщина на
        # всю карточку — это скучно, но читаемо, а пустой экран — нет.
        found = self._files.get(REGULAR) or next(iter(self._files.values()))
        for weight in FAMILY:
            self._files.setdefault(weight, found)

    @staticmethod
    def _usable(path: str) -> bool:
        """Есть ли в файле кириллица."""
        try:
            font = ImageFont.truetype(path, 32)
        except OSError as exc:
            LOGGER.warning("Шрифт %s не открылся: %s", path, exc)
            return False
        return all(_supported(font, char) for char in PROBE)

    # ------------------------------------------------------------------ выдача

    def get(self, weight: str, size: int) -> FontFile:
        """Начертание нужного кегля."""
        size = max(8, int(size))
        key = (weight, size)
        font = self._opened.get(key)
        if font is None:
            font = ImageFont.truetype(self._files.get(weight, self._files[REGULAR]), size)
            self._opened[key] = font
        return font

    def sanitize(self, text: str) -> str:
        """Заменяет символы, которых нет в шрифте.

        Ник приходит извне и может содержать что угодно — от иероглифов до
        emoji. Пустые квадраты посреди строки выглядят как сбой отрисовки,
        поэтому неизвестное заменяется точкой: видно, что символ был, и видно,
        что показать его нечем.
        """
        if text.isascii() and text.isprintable():
            return text
        result = []
        for char in text:
            if char in " \n\t" or self.knows(char):
                result.append(char)
                continue
            if char not in self._missing:
                self._missing.add(char)
                LOGGER.debug("В шрифте нет символа U+%04X", ord(char))
            result.append(UNKNOWN)
        return "".join(result)

    def knows(self, char: str) -> bool:
        """Есть ли символ в шрифте. Ответы запоминаются: проверка рисует растр."""
        answer = self._known.get(char)
        if answer is None:
            answer = _supported(self.get(REGULAR, 32), char)
            self._known[char] = answer
        return answer

    @property
    def directory(self) -> str:
        return self._directory


def _supported(font: FontFile, char: str) -> bool:
    """Знает ли шрифт этот символ.

    Сравнивается растр символа с растром заведомо отсутствующего: PIL молча
    подставляет вместо неизвестной буквы ``.notdef``, одинаковый для всех
    таких букв. Читать таблицы шрифта было бы точнее, но это зависимость от
    внутреннего устройства PIL, а растр от него не зависит.
    """
    if char in " \n\t":
        return True
    try:
        probe = font.getmask(char, mode="L")
        absent = font.getmask(ABSENT, mode="L")
    except (OSError, ValueError):  # pragma: no cover — испорченный файл
        return False
    if probe.size != absent.size:
        return True
    return bytes(probe) != bytes(absent)
