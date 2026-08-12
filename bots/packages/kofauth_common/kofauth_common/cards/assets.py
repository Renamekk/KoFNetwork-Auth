"""Общие ресурсы карточек: шрифты, шаблон и статические экраны.

Ресурсы лежат внутри пакета, а не рядом с ним. Так их не нужно отдельно
копировать в образ, монтировать томом и объяснять в docker-compose: где
установлен ``kofauth_common``, там же и шрифт с шаблоном. Развёртывание может
подменить каталог целиком через ``KOFAUTH_CARDS_DIR`` — например, чтобы
поставить свой баннер, — но по умолчанию всё уже на месте.

Ничего из этого не обязательно. Нет каталога, нет шрифта, нет шаблона —
карточки просто выключаются, и боты работают текстом, как работали до них.
Единственное, чего делать нельзя, — падать: оформление не может быть условием
работы подтверждения входа.
"""

from __future__ import annotations

import logging
import os
from typing import Final

from PIL import Image

from . import canvas, model, theme
from .fonts import FontBook, FontsUnavailable

LOGGER = logging.getLogger(__name__)

#: Каталог ресурсов по умолчанию — внутри пакета.
DEFAULT_DIR: Final = os.path.join(os.path.dirname(os.path.dirname(__file__)), "assets")

FONTS_DIR: Final = "fonts"
SCREENS_DIR: Final = "screens"

#: Готовые экраны. Главное меню — иллюстрация сети, шаблон — она же с вырезом
#: под панель, справка — запечённая карточка на каждую площадку.
MENU: Final = "menu.jpg"
TEMPLATE: Final = "card.jpg"


def baked_name(card: model.Card) -> str:
    """Имя файла запечённой карточки.

    Отпечаток в имени — не украшение. Справка печётся заранее, а её текст
    живёт в коде; без отпечатка правка списка команд оставила бы на диске
    картинку со старым списком, и заметить это было бы нечем. С отпечатком
    старый файл просто перестаёт находиться, и бот рисует справку сам.
    """
    return f"{card.kind}-{card.digest()}.jpg"


class Assets:
    """Каталог ресурсов одного развёртывания.

    Всё грузится по требованию и остаётся в памяти: шаблон нужен на каждой
    отрисовке, а читать и разжимать его каждый раз — те же десятки
    миллисекунд, ради экономии которых он и запекается.
    """

    __slots__ = ("_directory", "_fonts", "_template", "_screens", "_broken")

    def __init__(self, directory: str = "") -> None:
        self._directory = directory or DEFAULT_DIR
        self._fonts: FontBook | None = None
        self._template: Image.Image | None = None
        self._screens: dict[str, bytes] = {}
        self._broken = False

    @property
    def directory(self) -> str:
        return self._directory

    def path(self, *parts: str) -> str:
        return os.path.join(self._directory, *parts)

    # ------------------------------------------------------------------ шрифты

    def fonts(self) -> FontBook | None:
        """Книга шрифтов либо ``None``, если рисовать нечем."""
        if self._fonts is None and not self._broken:
            try:
                self._fonts = FontBook(self.path(FONTS_DIR))
            except FontsUnavailable as exc:
                LOGGER.warning("Карточки выключены: %s", exc)
                self._broken = True
        return self._fonts

    # ------------------------------------------------------------------ шаблон

    def template(self) -> Image.Image:
        """Подложка карточек.

        Нет запечённого файла — собирается тёмный фон с той же панелью. Экран
        выйдет без иллюстрации, но с данными, кнопками и рамкой: это заметно
        лучше, чем отсутствие экрана.
        """
        if self._template is not None:
            return self._template

        path = self.path(SCREENS_DIR, TEMPLATE)
        image: Image.Image | None = None
        if os.path.isfile(path):
            try:
                with Image.open(path) as source:
                    image = source.convert("RGB")
                if image.size != (theme.WIDTH, theme.HEIGHT):
                    image = image.resize((theme.WIDTH, theme.HEIGHT), Image.LANCZOS)
            except OSError as exc:
                LOGGER.warning("Шаблон %s не прочитан: %s", path, exc)
                image = None

        if image is None:
            LOGGER.info("Шаблон карточек не найден, рисую по тёмному фону")
            image = canvas.backdrop((theme.WIDTH, theme.HEIGHT))
            image = canvas.paste_layer(image, canvas.panel_layer(image.size))

        self._template = image
        return image

    # ------------------------------------------------------- статические экраны

    def screen(self, name: str) -> bytes | None:
        """Готовая картинка с диска. ``None`` — если файла нет."""
        cached = self._screens.get(name)
        if cached is not None:
            return cached
        path = self.path(SCREENS_DIR, name)
        if not os.path.isfile(path):
            return None
        try:
            with open(path, "rb") as handle:
                data = handle.read()
        except OSError as exc:
            LOGGER.warning("Экран %s не прочитан: %s", path, exc)
            return None
        self._screens[name] = data
        return data

    def menu(self) -> bytes | None:
        """Баннер главного меню — один и тот же у обоих ботов."""
        return self.screen(MENU)

    def screen_for(self, card: model.Card) -> bytes | None:
        """Запечённая картинка этой карточки, если она есть на диске."""
        return self.screen(baked_name(card))
