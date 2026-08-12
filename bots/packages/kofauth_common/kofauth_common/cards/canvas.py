"""Кисти карточек: панель, плитки, значки, текст.

Здесь нет ни одного решения о содержимом — только о том, как положить краску.
Что именно писать, решает :mod:`.model`, а :mod:`.render` расставляет это по
местам. Разделение нужно ровно затем, чтобы содержимое карточки можно было
проверить тестом, не открывая ни одной картинки.

Дорогое рисуется один раз. Свечение рамки — размытие по всему холсту, и делать
его на каждый запрос профиля означало бы тратить десятки миллисекунд на
результат, который никогда не меняется: панель у всех экранов одна и та же.
Поэтому панель со свечением впекается в шаблон (:mod:`.build`), а во время
работы бота остаются только текст и плитки.
"""

from __future__ import annotations

from typing import Final

from PIL import Image, ImageDraw, ImageFilter

from . import pixels, theme
from .fonts import BLACK, FontBook

#: Многоточие, которым обрывается не влезший текст. Одним символом, а не тремя
#: точками: три точки в моноширинном шрифте съедают три знакоместа — ровно те,
#: которых не хватило.
ELLIPSIS: Final = "…"


# --------------------------------------------------------------------- фигуры


def rounded(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], radius: int,
            fill: tuple[int, ...] | None = None,
            outline: tuple[int, ...] | None = None, width: int = 1) -> None:
    """Прямоугольник со скруглением — с проверкой, что он вообще существует.

    Отрицательная ширина случается на пустых списках и на самых узких экранах;
    ``rounded_rectangle`` в этом случае бросает исключение, и карточка
    пропадает целиком из-за строки, которую всё равно не было бы видно.
    """
    left, top, right, bottom = box
    if right <= left or bottom <= top:
        return
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def panel_layer(size: tuple[int, int]) -> Image.Image:
    """Панель со свечением отдельным слоем.

    Свечение делается размытием копии рамки, а не рисованием нескольких рамок
    подряд: несколько контуров дают ступеньки, размытие — ровный ореол, каким
    он и выглядит в макете.
    """
    box = (theme.PANEL_LEFT, theme.PANEL_TOP, theme.PANEL_RIGHT, theme.PANEL_BOTTOM)

    glow = Image.new("RGBA", size, (0, 0, 0, 0))
    pen = ImageDraw.Draw(glow)
    rounded(pen, box, theme.PANEL_RADIUS, outline=(*theme.RED, 210), width=6)
    glow = glow.filter(ImageFilter.GaussianBlur(14))

    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    pen = ImageDraw.Draw(layer)
    rounded(pen, box, theme.PANEL_RADIUS, fill=(*theme.PANEL_FILL, theme.PANEL_ALPHA))
    # Внутренняя тонкая линия и внешняя яркая: так рамка читается как
    # светящаяся трубка, а не как обведённый маркером прямоугольник.
    rounded(pen, box, theme.PANEL_RADIUS, outline=(*theme.RED, 255), width=3)
    inner = (box[0] + 7, box[1] + 7, box[2] - 7, box[3] - 7)
    rounded(pen, inner, theme.PANEL_RADIUS - 7, outline=(*theme.RED_DARK, 150), width=2)

    glow.alpha_composite(layer)
    return glow


def backdrop(size: tuple[int, int]) -> Image.Image:
    """Запасной фон, когда иллюстрации нет.

    Нужен не для красоты: без него отсутствие файла шаблона означало бы
    отсутствие карточек вообще. Тёмный градиент с виньеткой — то же, что и в
    макете под панелью, только без сакуры.
    """
    width, height = size
    base = Image.new("RGB", size, (8, 4, 6))
    pen = ImageDraw.Draw(base)
    for y in range(height):
        # Тёплый низ и почти чёрный верх: свет в макете идёт снизу, от углей.
        share = y / max(1, height - 1)
        pen.line(
            [(0, y), (width, y)],
            fill=(int(8 + 26 * share), int(4 + 6 * share), int(6 + 9 * share)),
        )
    return base


def paste_layer(base: Image.Image, layer: Image.Image) -> Image.Image:
    """Накладывает слой с прозрачностью на непрозрачную основу."""
    result = base.convert("RGBA")
    result.alpha_composite(layer)
    return result.convert("RGB")


# --------------------------------------------------------------------- значки


def pixel_icon(draw: ImageDraw.ImageDraw, grid: tuple[str, ...],
               box: tuple[int, int, int, int], fill: tuple[int, ...]) -> None:
    """Рисует значок по сетке символов.

    Клетка округляется до целых пикселей и только потом центрируется: дробный
    шаг размывает края, и пиксельный значок перестаёт быть пиксельным — ровно
    то, ради чего он и рисуется сеткой.
    """
    left, top, right, bottom = box
    rows, columns = len(grid), len(grid[0])
    cell = min((right - left) // columns, (bottom - top) // rows)
    if cell < 1:
        return
    offset_x = left + ((right - left) - cell * columns) // 2
    offset_y = top + ((bottom - top) - cell * rows) // 2
    for row, line in enumerate(grid):
        for column, mark in enumerate(line):
            if mark == ".":
                continue
            x = offset_x + column * cell
            y = offset_y + row * cell
            draw.rectangle((x, y, x + cell - 1, y + cell - 1), fill=fill)


def icon_tile(draw: ImageDraw.ImageDraw, name: str, box: tuple[int, int, int, int],
              fill: tuple[int, ...] = theme.RED) -> None:
    """Плитка со значком: подложка, красный кант, пиксельный рисунок."""
    rounded(draw, box, theme.TILE_RADIUS,
            fill=(*theme.TILE_FILL, theme.TILE_ALPHA), outline=(*theme.RED_DIM, 190),
            width=2)
    grid = pixels.glyph(name)
    if grid is None:
        return
    left, top, right, bottom = box
    inset = max(5, (right - left) // 8)
    pixel_icon(draw, grid,
               (left + inset, top + inset, right - inset, bottom - inset), fill)


def divider(draw: ImageDraw.ImageDraw, y: int, left: int, right: int,
            ornament: bool = True) -> None:
    """Разделитель: тонкая линия и ромбик посередине, как в макете."""
    draw.line([(left, y), (right, y)], fill=(*theme.RED_DARK, 255), width=1)
    if not ornament:
        return
    middle = (left + right) // 2
    # Под ромбом линия стирается: иначе он выглядит наклеенным на неё.
    draw.rectangle((middle - 22, y - 2, middle + 22, y + 2), fill=theme.PANEL_FILL)
    pixel_icon(draw, pixels.ORNAMENT, (middle - 9, y - 9, middle + 9, y + 9), theme.RED)


# ---------------------------------------------------------------------- текст


class Text:
    """Текстовые операции, которым нужен шрифт.

    Собраны в объект, потому что каждой из них нужны и книга шрифтов, и холст,
    и передавать эту пару в семь функций подряд — верный способ однажды
    нарисовать заголовок кеглем строки.
    """

    __slots__ = ("_draw", "_fonts")

    def __init__(self, draw: ImageDraw.ImageDraw, fonts: FontBook) -> None:
        self._draw = draw
        self._fonts = fonts

    def width(self, text: str, weight: str, size: int) -> int:
        return int(self._draw.textlength(text, font=self._fonts.get(weight, size)))

    def fit(self, text: str, weight: str, size: int, limit: int) -> tuple[str, int]:
        """Подгоняет строку под ширину: сперва кеглем, потом обрезкой.

        Порядок именно такой. Уменьшенная строка читается целиком, обрезанная
        теряет конец — а конец у ника или у адреса как раз и отличает один от
        другого. Поэтому обрезка включается только тогда, когда уменьшать
        дальше некуда: слишком мелкий текст на телефоне не читается вовсе.
        """
        text = self._fonts.sanitize(text)
        if limit <= 0:
            return "", size
        for share in theme.SHRINK:
            step = max(12, int(size * share))
            if self.width(text, weight, step) <= limit:
                return text, step
        return self.clip(text, weight, max(12, int(size * theme.SHRINK[-1])), limit), \
            max(12, int(size * theme.SHRINK[-1]))

    def clip(self, text: str, weight: str, size: int, limit: int) -> str:
        """Обрезает строку по ширине, добавляя многоточие.

        Двоичным поиском, а не посимвольно: ник бывает и в двести символов,
        а обрезка вызывается по нескольку раз на карточку.
        """
        if self.width(text, weight, size) <= limit:
            return text
        low, high = 0, len(text)
        while low < high:
            middle = (low + high + 1) // 2
            if self.width(text[:middle] + ELLIPSIS, weight, size) <= limit:
                low = middle
            else:
                high = middle - 1
        return (text[:low] + ELLIPSIS) if low else ELLIPSIS

    def draw(self, xy: tuple[int, int], text: str, weight: str, size: int,
             fill: tuple[int, int, int], anchor: str = "ls") -> int:
        """Пишет строку и возвращает её ширину.

        Обводки здесь нет намеренно. Пиксельный шрифт набран квадратами
        размером с просвет внутри буквы, и обводка в один пиксель эти просветы
        затягивает: «Регистрация» превращается в «Ргиотрация». Толщину даёт
        начертание, а не обводка, — для того и лежат в ресурсах три файла.
        """
        font = self._fonts.get(weight, size)
        self._draw.text(xy, text, font=font, fill=fill, anchor=anchor)
        return int(self._draw.textlength(text, font=font))

    def title(self, text: str, centre_x: int, centre_y: int, limit: int) -> None:
        """Заголовок экрана с ромбами по бокам.

        Ромбы ставятся по фактической ширине надписи, а не по краям панели:
        привязанные к панели, они уезжали бы от короткого «ЗАЩИТА» на полэкрана
        и упирались бы в длинную «ИСТОРИЯ ВХОДОВ».
        """
        text = text.upper()
        gap = 26
        room = limit - 2 * (gap + 22)
        text, size = self.fit(text, BLACK, theme.SIZE_TITLE, room)
        width = self.draw((centre_x, centre_y), text, BLACK, size, theme.CREAM,
                          anchor="mm")
        for side in (-1, 1):
            x = centre_x + side * (width // 2 + gap + 11)
            pixel_icon(self._draw, pixels.ORNAMENT,
                       (x - 11, centre_y - 11, x + 11, centre_y + 11), theme.RED)

    def paragraph(self, text: str, weight: str, size: int, limit: int) -> list[str]:
        """Разбивает строку на строки по ширине.

        Перенос по словам; слово, которое само не влезает, обрезается. Иначе
        одно длинное значение — ссылка или ник без пробелов — растянуло бы
        абзац за край панели.
        """
        text = self._fonts.sanitize(text)
        words = text.split(" ")
        lines: list[str] = []
        current = ""
        for word in words:
            candidate = f"{current} {word}".strip()
            if not candidate:
                continue
            if self.width(candidate, weight, size) <= limit:
                current = candidate
                continue
            if current:
                lines.append(current)
            current = word if self.width(word, weight, size) <= limit \
                else self.clip(word, weight, size, limit)
        if current:
            lines.append(current)
        return lines or [""]
