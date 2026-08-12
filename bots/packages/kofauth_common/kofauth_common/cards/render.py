"""Отрисовка карточек.

Один вход: :meth:`Renderer.render` получает содержимое из :mod:`.model` и
отдаёт готовый JPEG. Внутри — три раскладки: поля («Подпись: значение»),
список строк и разделы справки. Больше не нужно: экранов у ботов пять, и все
пять укладываются в эти три.

Почему JPEG. Над панелью лежит фотореалистичная иллюстрация — сакура, вода,
фонари. В PNG такой кадр весит около двух мегабайт, и телефон на мобильной
сети скачивает его секундами; в JPEG он весит втрое-вчетверо меньше, а текст
остаётся чётким. Прореживание цветности выключено намеренно: красный текст на
чёрном — худший случай для 4:2:0, буквы расплываются розовым.

Ничего не может уронить бота. Отрисовка идёт в отдельном потоке
(:mod:`.service`), а её отказ означает, что экран будет показан текстом, —
поэтому здесь нет ни одного места, где неожиданное значение приводит к
исключению вместо прочерка.
"""

from __future__ import annotations

import io
import logging
from typing import Final

from PIL import Image, ImageDraw

from . import canvas, model, pixels, theme
from .fonts import BLACK, BOLD, REGULAR, FontBook

LOGGER = logging.getLogger(__name__)

#: Промежуток между колонками табличной строки.
COLUMN_GAP: Final = 18

#: Строка со значком и подписью помещается в высоту, начиная с которой имеет
#: смысл рисовать приписку. Ниже — приписка не влезает, и вместо неё остаётся
#: значок исхода: он занимает нисколько и читается быстрее.
SUB_ROW_MIN: Final = 58


class Renderer:
    """Рисовальщик карточек поверх готового шаблона.

    :param template: подложка с иллюстрацией и пустой панелью. Панель впечена
        в неё заранее: её свечение — размытие по всему холсту, и делать его на
        каждый запрос профиля значило бы платить десятками миллисекунд за
        картинку, которая всегда одна и та же.
    """

    __slots__ = ("_fonts", "_template", "_quality")

    def __init__(self, fonts: FontBook, template: Image.Image, quality: int = 84) -> None:
        self._fonts = fonts
        self._template = template.convert("RGB")
        self._quality = max(60, min(96, quality))

    # ------------------------------------------------------------------ вход

    def render(self, card: model.Card) -> bytes:
        """Карточка в виде готового к отправке JPEG."""
        image = self._template.copy()
        overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
        pen = ImageDraw.Draw(overlay)
        text = canvas.Text(pen, self._fonts)

        left = theme.PANEL_LEFT + theme.PAD_X
        right = theme.PANEL_RIGHT - theme.PAD_X

        text.title(card.title, (theme.PANEL_LEFT + theme.PANEL_RIGHT) // 2,
                   theme.PANEL_TOP + theme.TITLE_CENTER, right - left)
        canvas.divider(pen, theme.PANEL_TOP + theme.TITLE_DIVIDER, left, right)

        top = theme.PANEL_TOP + theme.CONTENT_TOP
        bottom = theme.PANEL_BOTTOM - theme.PAD_BOTTOM
        if card.footer:
            bottom -= theme.SIZE_FOOTER + 18
            self._footer(pen, text, card, left, right,
                         theme.PANEL_BOTTOM - theme.PAD_BOTTOM)

        if card.sections:
            self._sections(pen, text, card, left, right, top, bottom)
        elif card.entries:
            self._entries(pen, text, card, left, right, top, bottom)
        else:
            self._fields(pen, text, card, left, right, top, bottom)

        image = canvas.paste_layer(image, overlay)
        return self._encode(image)

    def _encode(self, image: Image.Image) -> bytes:
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=self._quality, subsampling=0,
                   optimize=True)
        return buffer.getvalue()

    # ---------------------------------------------------------------- подвал

    def _footer(self, pen: ImageDraw.ImageDraw, text: canvas.Text, card: model.Card,
                left: int, right: int, baseline: int) -> None:
        canvas.divider(pen, baseline - theme.SIZE_FOOTER - 14, left, right,
                       ornament=False)
        body, size = text.fit(card.footer, REGULAR, theme.SIZE_FOOTER, right - left)
        text.draw(((left + right) // 2, baseline), body, REGULAR, size,
                  theme.colour(card.footer_tone), anchor="ms")

    # ------------------------------------------------------- поля «имя: значение»

    def _fields(self, pen: ImageDraw.ImageDraw, text: canvas.Text, card: model.Card,
                left: int, right: int, top: int, bottom: int) -> None:
        rows = card.fields
        if not rows:
            self._nothing(text, card, left, right, top, bottom)
            return

        height = _row_height(bottom - top, len(rows), theme.ROW_MIN, theme.ROW_MAX)
        start = top + max(0, (bottom - top - height * len(rows)) // 2)

        for index, row in enumerate(rows):
            row_top = start + index * height
            centre = row_top + height // 2
            tile = min(theme.TILE_SIZE, height - 12)
            body_left = left
            if tile >= 24:
                canvas.icon_tile(
                    pen, row.icon,
                    (left, centre - tile // 2, left + tile, centre + tile // 2),
                    theme.colour(row.tone) if row.tone == theme.TONE_WARN else theme.RED,
                )
                body_left = left + tile + theme.TILE_GAP

            self._field_body(text, row, body_left, right, centre, height)
            if index + 1 < len(rows):
                canvas.divider(pen, row_top + height, left, right)

    def _field_body(self, text: canvas.Text, row: model.Field, left: int, right: int,
                    centre: int, height: int) -> None:
        """Подпись и значение: в одну строку, если помещаются, иначе в две.

        Перенос именно такой: подпись сверху, значение под ней. Разорванное
        посередине значение — «12.08.2026 15:10» и «UTC» на разных строках —
        читается хуже, чем то же значение целиком строкой ниже.
        """
        limit = right - left
        label = f"{row.label}:"
        size = theme.SIZE_ROW if height >= 70 else theme.SIZE_ROW_SMALL
        colour = theme.colour(row.tone)

        label_width = text.width(label, BOLD, size)
        gap = 12
        value, value_size = text.fit(row.value, BOLD, size, limit - label_width - gap)

        if value_size == size and label_width + gap + text.width(value, BOLD, size) <= limit:
            text.draw((left, centre), label, BOLD, size, theme.TEXT, anchor="lm")
            text.draw((left + label_width + gap, centre), value, BOLD, size, colour,
                      anchor="lm")
            return

        # Двумя строками: подпись обычным кеглем, значение — подогнанным.
        small = max(theme.SIZE_ROW_SMALL, int(size * 0.86))
        value, value_size = text.fit(row.value, BOLD, small, limit)
        step = small + 6
        text.draw((left, centre - step // 2), label, BOLD, small, theme.TEXT, anchor="lm")
        text.draw((left, centre + step // 2), value, BOLD, value_size, colour,
                  anchor="lm")

    # ------------------------------------------------------------- список строк

    def _entries(self, pen: ImageDraw.ImageDraw, text: canvas.Text, card: model.Card,
                 left: int, right: int, top: int, bottom: int) -> None:
        rows = card.entries
        if not rows:
            self._nothing(text, card, left, right, top, bottom)
            return

        available = bottom - top
        height = _row_height(available, len(rows), theme.ROW_MIN - 12, theme.ENTRY_MAX)
        fits = max(1, available // max(1, height))
        rows = rows[:fits]

        size = min(theme.SIZE_ENTRY, max(18, height - 12))
        widths = self._columns(text, rows, size, right - left)
        start = top + max(0, (available - height * len(rows)) // 2)

        for index, row in enumerate(rows):
            row_top = start + index * height
            box = (left, row_top + 3, right, row_top + height - 3)
            canvas.rounded(pen, box, 12, fill=(*theme.ENTRY_FILL, theme.ENTRY_ALPHA))

            centre = (box[1] + box[3]) // 2
            sub = row.sub if height >= SUB_ROW_MIN else ""
            if sub:
                centre = box[1] + (box[3] - box[1]) // 2 - theme.SIZE_SUB // 2

            # Значок исхода не отключается вместе с уменьшением строки: в
            # длинном списке он и есть единственное, что отличает неудачную
            # попытку входа от удачной с одного взгляда.
            icon = min(height - 8, 40)
            body_left = left + 12
            if icon >= 14:
                canvas.pixel_icon(
                    pen, _icon_grid(row.icon),
                    (body_left, centre - icon // 2, body_left + icon, centre + icon // 2),
                    theme.GREEN if row.icon == "check" else theme.RED,
                )
                body_left += icon + 14

            cursor = body_left
            for column, width in zip(row.columns, widths, strict=False):
                body, step = text.fit(column.text, BOLD, size, width)
                text.draw((cursor, centre), body, BOLD, step,
                          theme.colour(column.tone), anchor="lm")
                cursor += width + COLUMN_GAP

            if sub:
                body, step = text.fit(sub, REGULAR, theme.SIZE_SUB,
                                      right - body_left - 12)
                text.draw((body_left, box[3] - 10), body, REGULAR, step,
                          theme.MUTED, anchor="ls")

    def _columns(self, text: canvas.Text, rows: tuple[model.Entry, ...], size: int,
                 limit: int) -> list[int]:
        """Ширины колонок, общие для всех строк списка.

        Общие — потому что список читается сверху вниз: даты, стоящие в
        столбик, взгляд сравнивает мгновенно, а те же даты, съехавшие вслед за
        длиной соседней ячейки, приходится перечитывать по одной.

        Если сумма не влезает, лишнее снимается с самой широкой колонки: она и
        есть та, из-за которой строка не помещается.
        """
        count = max((len(row.columns) for row in rows), default=0)
        if not count:
            return []
        widths = [
            max((text.width(row.columns[i].text, BOLD, size)
                 for row in rows if i < len(row.columns)), default=0)
            for i in range(count)
        ]
        # Место под значок и поля строки.
        room = limit - 12 - 40 - 14 - 12 - COLUMN_GAP * (count - 1)
        while sum(widths) > room and max(widths) > 40:
            widths[widths.index(max(widths))] -= 8
        return widths

    # ------------------------------------------------------------------ разделы

    def _sections(self, pen: ImageDraw.ImageDraw, text: canvas.Text, card: model.Card,
                  left: int, right: int, top: int, bottom: int) -> None:
        """Справка: заголовок раздела и строки под ним.

        Высота раздела считается по числу строк, а не поровну: раздел из двух
        строк рядом с разделом из шести, вытянутые до одинаковой высоты,
        оставляют посреди карточки пустое поле.
        """
        sections = card.sections
        if not sections:
            self._nothing(text, card, left, right, top, bottom)
            return

        weights = [1 + len(section.lines) for section in sections]
        available = bottom - top - 12 * (len(sections) - 1)
        cursor = top

        for index, section in enumerate(sections):
            height = available * weights[index] // sum(weights)
            tile = min(theme.TILE_SIZE, height - 8, 62)
            body_left = left
            if tile >= 24:
                canvas.icon_tile(pen, section.icon,
                                 (left, cursor + 6, left + tile, cursor + 6 + tile))
                body_left = left + tile + theme.TILE_GAP

            line_height = min(theme.SIZE_LINE + 10,
                              max(22, (height - theme.SIZE_SECTION - 10)
                                  // max(1, len(section.lines))))
            heading, size = text.fit(section.heading, BLACK, theme.SIZE_SECTION,
                                     right - body_left)
            text.draw((body_left, cursor + size + 6), heading, BLACK, size, theme.RED,
                      anchor="ls")

            line_top = cursor + size + 6 + line_height
            for line in section.lines:
                body, step = text.fit(line, REGULAR, theme.SIZE_LINE, right - body_left)
                text.draw((body_left, line_top), body, REGULAR, step, theme.TEXT,
                          anchor="ls")
                line_top += line_height

            cursor += height + 12
            if index + 1 < len(sections):
                canvas.divider(pen, cursor - 6, left, right)

    # ------------------------------------------------------------------ пусто

    def _nothing(self, text: canvas.Text, card: model.Card, left: int, right: int,
                 top: int, bottom: int) -> None:
        """Пустой экран объясняется словами.

        Пустая панель читается как сбой отрисовки, а не как «записей нет», и
        человек идёт жаловаться на сломанного бота вместо того, чтобы понять
        ответ. Слова берутся у карточки: «входов пока не было» отвечает на
        вопрос, а «пусто» — нет.
        """
        body, size = text.fit(card.empty or "Пока пусто", REGULAR, theme.SIZE_ROW,
                              right - left)
        text.draw(((left + right) // 2, (top + bottom) // 2), body, REGULAR, size,
                  theme.MUTED, anchor="mm")


def _icon_grid(name: str) -> tuple[str, ...]:
    """Сетка значка. Незнакомое имя — монитор: строку списка рисовать всё равно."""
    return pixels.glyph(name) or pixels.SCREEN


def _row_height(available: int, count: int, low: int, high: int) -> int:
    """Высота строки: поровну, но в разумных пределах.

    Верхний предел не даёт трём строкам расползтись по всей панели, нижний —
    десяти строкам сжаться до нечитаемого. Между ними высота делится поровну,
    поэтому список из четырёх записей и список из десяти выглядят одним и тем
    же экраном, а не двумя разными.
    """
    if count <= 0:
        return low
    return max(low, min(high, available // count))
