"""Сборка ресурсов карточек.

Запускается руками и редко — когда меняется иллюстрация или тексты справки:

    python -m kofauth_common.cards.build --source ~/art

Из исходных изображений получаются три вещи: баннер главного меню, шаблон
динамических карточек и запечённая справка на каждую площадку. Всё это ложится
в ресурсы пакета и попадает в репозиторий: во время работы бот ничего не
собирает и ничего не скачивает.

Почему шаблон, а не «нарисуем панель на каждой карточке». Панель со свечением
— это размытие по всему холсту, десятки миллисекунд на каждую отрисовку ради
результата, который никогда не меняется. Она впекается один раз здесь.

Почему вырез. Исходные макеты уже содержат панель с текстом-примером. Оставить
её нельзя — под нашей панелью просвечивал бы чужой «Ник: Inroka», — а вырезать
приходится с запасом: свечение уходит за края рамки.
"""

from __future__ import annotations

import argparse
import os
import sys
from typing import Final

from PIL import Image, ImageDraw

from . import canvas, model, theme
from .assets import DEFAULT_DIR, MENU, SCREENS_DIR, TEMPLATE, Assets, baked_name
from .render import Renderer

#: Ширина баннера главного меню. Он горизонтальный и показывается шапкой, а не
#: карточкой, поэтому у него свой размер.
MENU_WIDTH: Final = 1100

#: Качество шаблона. Выше, чем у готовых экранов, и намеренно: поверх шаблона
#: пишется текст, и картинка сохраняется ещё раз — потери сложились бы дважды.
TEMPLATE_QUALITY: Final = 92

#: Качество готовых к отправке экранов. Подобрано по весу: на плоской тёмной
#: панели разницы с 90 не видно даже вплотную, а картинка легче почти на треть.
#: Прореживание цветности при этом выключено — красный текст на чёрном первым
#: же и расплывается.
SCREEN_QUALITY: Final = 86

#: Полоски у краёв, по которым восстанавливается фон под вырезанной панелью.
#: Берутся снаружи панели: там настоящий фон макета — виньетка и угли.
SAMPLE: Final = 26

#: Ширина растушёвки по краям заплатки. Без неё граница вырезанной области
#: читается как прочерченная по кадру линия.
FEATHER: Final = 40


def build(source: str = "", target: str = "", menu: str = "", card: str = "") -> list[str]:
    """Собирает ресурсы. Возвращает список записанных файлов.

    :param source: каталог макетов; имена угадываются по словам в них
    :param menu: файл баннера, если угадывать не надо
    :param card: файл-основа шаблона карточек
    """
    directory = target or DEFAULT_DIR
    screens = os.path.join(directory, SCREENS_DIR)
    os.makedirs(screens, exist_ok=True)

    written: list[str] = []
    banner = menu or _find(source, ("menu", "главное"))
    if banner:
        written.append(_write(os.path.join(screens, MENU), _banner(banner)))

    base = card or _find(source, ("card", "profile", "профиль", "template"))
    if base:
        written.append(_write(os.path.join(screens, TEMPLATE), _template(base),
                              TEMPLATE_QUALITY))

    written.extend(_help(directory, screens))
    return written


# ------------------------------------------------------------------- главное меню


def _banner(path: str) -> Image.Image:
    """Баннер главного меню: то же изображение, только легче.

    Уменьшение — не экономия ради экономии. Исходный кадр весит два с половиной
    мегабайта; на мобильной сети это секунды ожидания перед каждым открытием
    меню, а разницы на экране телефона не видно.
    """
    with Image.open(path) as source:
        image = source.convert("RGB")
    if image.width > MENU_WIDTH:
        height = round(image.height * MENU_WIDTH / image.width)
        image = image.resize((MENU_WIDTH, height), Image.LANCZOS)
    return image


# ----------------------------------------------------------------------- шаблон


def _template(path: str) -> Image.Image:
    """Шаблон карточек: иллюстрация, вырез под панель и сама панель.

    Порядок важен. Сначала вырезается прежняя панель — по исходному размеру,
    пока сглаживание уменьшения не размазало её края по фону. Только потом
    изображение уменьшается и на него кладётся наша панель.
    """
    with Image.open(path) as source:
        image = source.convert("RGB")

    _erase(image)
    image = image.resize((theme.WIDTH, theme.HEIGHT), Image.LANCZOS)
    return canvas.paste_layer(image, canvas.panel_layer(image.size))


def _erase(image: Image.Image) -> None:
    """Затирает область панели фоном, взятым по краям кадра.

    Строка за строкой: слева и справа от панели берутся средние цвета полосок,
    и между ними кладётся переход. Так восстанавливается и виньетка, и
    свечение снизу — не идеально, но идеально и не нужно: сверху ляжет
    непрозрачная панель, а по углам скругления разница в пару тонов не видна.
    """
    scale_x = image.width / theme.WIDTH
    scale_y = image.height / theme.HEIGHT
    left = int((theme.PANEL_LEFT - theme.PANEL_BLEED) * scale_x)
    right = int((theme.PANEL_RIGHT + theme.PANEL_BLEED) * scale_x)
    top = int((theme.PANEL_TOP - theme.PANEL_BLEED) * scale_y)
    bottom = int((theme.PANEL_BOTTOM + theme.PANEL_BLEED) * scale_y)

    left = max(SAMPLE, min(left, image.width - SAMPLE))
    right = max(left + 1, min(right, image.width - SAMPLE))
    top = max(0, top)
    bottom = min(image.height, bottom)
    if bottom <= top:
        return

    pixels = image.load()
    # Заплатка собирается шириной в два пикселя и растягивается: растяжение
    # даёт ровно тот же переход слева направо, что и покраска по точке, только
    # считает его не Python, а PIL.
    strip = Image.new("RGB", (2, bottom - top))
    for y in range(top, bottom):
        strip.putpixel((0, y - top), _average(pixels, left - SAMPLE, left, y))
        strip.putpixel((1, y - top), _average(pixels, right, right + SAMPLE, y))

    patch = strip.resize((right - left, bottom - top), Image.BILINEAR)
    image.paste(patch, (left, top), _feather(patch.size))


def _feather(size: tuple[int, int]) -> Image.Image:
    """Маска заплатки: непрозрачная внутри, сходящая на нет по краям.

    Без неё край заплатки читается как прочерченная по кадру линия — панель
    закрывает середину, но не углы и не поля вокруг неё, и ровно там жёсткая
    граница и видна.
    """
    width, height = size
    mask = Image.new("L", size, 255)
    pen = ImageDraw.Draw(mask)
    fade = min(FEATHER, width // 2, height // 2)
    for step in range(fade):
        value = int(255 * (step + 1) / (fade + 1))
        pen.rectangle((step, step, width - 1 - step, height - 1 - step), outline=value)
    return mask


def _average(pixels, x0: int, x1: int, y: int) -> tuple[int, int, int]:
    """Средний цвет полоски. Медиана была бы честнее, но угли редки."""
    count = max(1, x1 - x0)
    total = [0, 0, 0]
    for x in range(x0, x1):
        colour = pixels[x, y]
        for channel in range(3):
            total[channel] += colour[channel]
    return (total[0] // count, total[1] // count, total[2] // count)


# ---------------------------------------------------------------------- справка


def _help(directory: str, screens: str) -> list[str]:
    """Печёт справку каждой площадки и убирает устаревшие варианты."""
    assets = Assets(directory)
    fonts = assets.fonts()
    if fonts is None:
        print("Шрифты не найдены — справка не собрана", file=sys.stderr)
        return []

    renderer = Renderer(fonts, assets.template(), quality=SCREEN_QUALITY)
    written: list[str] = []
    current: set[str] = set()

    for platform in model.HELP_SECTIONS:
        card = model.help_card(platform)
        name = baked_name(card)
        current.add(name)
        path = os.path.join(screens, name)
        with open(path, "wb") as handle:
            handle.write(renderer.render(card))
        written.append(path)

    # Старые отпечатки удаляются здесь же: иначе каталог ресурсов копил бы по
    # картинке на каждую правку текста команд, и все они попадали бы в образ.
    for name in os.listdir(screens):
        if name.startswith("help-") and name not in current:
            os.remove(os.path.join(screens, name))
    return written


# ------------------------------------------------------------------ вспомогательное


def _find(source: str, hints: tuple[str, ...]) -> str:
    """Ищет исходный файл по подсказкам в имени.

    Макеты приходят из графического редактора с именами вроде
    «ChatGPT Image 12 авг. 2026 г., 18_14_54.png», поэтому имя задаётся либо
    точно, либо угадывается по слову в нём.
    """
    if os.path.isfile(source):
        return source
    if not os.path.isdir(source):
        return ""
    for name in sorted(os.listdir(source)):
        lowered = name.lower()
        if lowered.endswith((".png", ".jpg", ".jpeg")) \
                and any(hint in lowered for hint in hints):
            return os.path.join(source, name)
    return ""


def _write(path: str, image: Image.Image, quality: int = SCREEN_QUALITY) -> str:
    image.save(path, format="JPEG", quality=quality, subsampling=0, optimize=True)
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Сборка ресурсов карточек KoF Network")
    parser.add_argument("--source", default="",
                        help="каталог с исходными изображениями макетов")
    parser.add_argument("--target", default="",
                        help="каталог ресурсов; по умолчанию — внутри пакета")
    parser.add_argument("--menu", default="", help="файл баннера главного меню")
    parser.add_argument("--card", default="", help="файл-основа шаблона карточек")
    options = parser.parse_args(argv)

    written = build(source=options.source, target=options.target,
                    menu=options.menu, card=options.card)
    for path in written:
        print(f"{os.path.getsize(path) / 1024:7.0f} КиБ  {path}")
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
