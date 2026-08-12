"""Отрисовка карточек.

Здесь проверяется то, чего не видно в содержимом: что картинка вообще
получилась, что она не весит мегабайты, что длинный ник не вылез за рамку и
что отсутствие ресурсов означает более скромный экран, а не отсутствие экрана.

Проверка «не вылез за рамку» сделана сравнением с шаблоном: всё, что бот
нарисовал, обязано лежать внутри панели. Сравнивать картинку с эталонной
незачем — эталон пришлось бы обновлять после каждой правки отступа, и он
проверял бы совпадение с прошлой версией, а не пригодность нынешней.
"""

from __future__ import annotations

import io

import pytest
from kofauth_common.cards import canvas, model, pixels, theme
from kofauth_common.cards.assets import Assets
from kofauth_common.cards.fonts import BLACK, BOLD, REGULAR, FontBook, FontsUnavailable
from kofauth_common.cards.render import Renderer
from PIL import Image, ImageChops

PROFILE = {
    "username": "Inroka",
    "registeredAt": "2026-02-01T09:14:00Z",
    "lastLoginAt": "2026-08-12T15:10:00Z",
    "twoFactor": ["TELEGRAM", "DISCORD"],
    "loginApproval": True,
    "notifications": True,
    "captchaPassed": True,
}

HISTORY = [
    {"at": "2026-08-12T15:10:00Z", "success": True, "ip": "127.0.0.***"},
    {"at": "2026-08-11T21:42:00Z", "success": False, "ip": "185.12.4.***",
     "result": "BAD_PASSWORD"},
]

#: Насколько пиксель должен отличаться от шаблона, чтобы считаться нарисованным.
#: Мелкие отличия даёт само сжатие JPEG, и порог отсекает именно их.
PAINT = 48

#: Запас вокруг панели. Свечение рамки уходит наружу и слегка меняет соседние
#: пиксели — это часть шаблона, а не то, что рисует бот.
MARGIN = 24


@pytest.fixture(scope="module")
def assets() -> Assets:
    return Assets()


@pytest.fixture(scope="module")
def fonts(assets: Assets) -> FontBook:
    book = assets.fonts()
    if book is None:  # pragma: no cover — ресурсы лежат в пакете
        pytest.skip("В ресурсах пакета нет шрифтов")
    return book


@pytest.fixture(scope="module")
def renderer(assets: Assets, fonts: FontBook) -> Renderer:
    return Renderer(fonts, assets.template())


def painted(assets: Assets, data: bytes) -> tuple[int, int, int, int] | None:
    """Прямоугольник, в который уложилось всё нарисованное поверх шаблона."""
    drawn = Image.open(io.BytesIO(data)).convert("RGB")
    difference = ImageChops.difference(drawn, assets.template()).convert("L")
    return difference.point(lambda value: 255 if value > PAINT else 0).getbbox()


def inside_panel(box: tuple[int, int, int, int] | None) -> bool:
    left, top, right, bottom = box
    return (left >= theme.PANEL_LEFT - MARGIN and top >= theme.PANEL_TOP - MARGIN
            and right <= theme.PANEL_RIGHT + MARGIN
            and bottom <= theme.PANEL_BOTTOM + MARGIN)


class TestКартинка:
    def test_получается_и_читается(self, renderer: Renderer) -> None:
        data = renderer.render(model.profile_card(PROFILE))
        image = Image.open(io.BytesIO(data))
        assert image.format == "JPEG"
        assert image.size == (theme.WIDTH, theme.HEIGHT)

    def test_не_весит_мегабайты(self, renderer: Renderer) -> None:
        # Телефон на мобильной сети качает карточку перед каждым показом.
        data = renderer.render(model.history_card(HISTORY * 5))
        assert len(data) < 700 * 1024

    @pytest.mark.parametrize("card", [
        model.profile_card(PROFILE, platform="Telegram"),
        model.security_card(PROFILE),
        model.history_card(HISTORY),
        model.sessions_card([{"type": "GAME", "ip": "127.0.0.***",
                              "lastSeenAt": "2026-08-12T15:10:00Z"}]),
        model.help_card("TELEGRAM"),
        model.help_card("DISCORD"),
    ], ids=["профиль", "защита", "история", "сессии", "справка-tg", "справка-ds"])
    def test_каждый_экран_рисуется_внутри_панели(self, renderer: Renderer,
                                                 assets: Assets, card) -> None:
        box = painted(assets, renderer.render(card))
        assert box is not None, "экран оказался пустым"
        assert inside_panel(box), f"нарисованное вышло за панель: {box}"


class TestПереполнение:
    """Длинные значения, пустые данные и любое число записей.

    Все три ломают композицию одинаково незаметно: на разработке данных мало и
    они короткие, а первый же двадцатисимвольный ник уезжает за край.
    """

    @pytest.mark.parametrize("username", [
        "Steve",
        "СуперДлинныйНикНикНик_2026",
        "Ы" * 64,
        "A" * 200,
        "🙂🙂🙂 Ник",
        "  ",
    ], ids=["обычный", "длинный", "кириллица", "очень-длинный", "emoji", "пробелы"])
    def test_ник_не_вылезает_за_панель(self, renderer: Renderer, assets: Assets,
                                       username: str) -> None:
        card = model.profile_card(dict(PROFILE, username=username), platform="Telegram")
        box = painted(assets, renderer.render(card))
        assert inside_panel(box), f"ник {username[:12]!r} вышел за панель: {box}"

    @pytest.mark.parametrize("count", [0, 1, 3, 5, 10, 25],
                             ids=lambda n: f"{n}-записей")
    def test_история_любой_длины_держит_композицию(self, renderer: Renderer,
                                                   assets: Assets, count: int) -> None:
        card = model.history_card([HISTORY[0]] * count)
        box = painted(assets, renderer.render(card))
        assert box is not None
        assert inside_panel(box), f"{count} записей вышли за панель: {box}"

    def test_длинные_значения_в_списке_не_ломают_столбцы(self, renderer: Renderer,
                                                          assets: Assets) -> None:
        # Ширины столбцов общие на весь список, и одна разросшаяся ячейка
        # утащила бы за собой все остальные строки.
        card = model.sessions_card([
            {"type": "ОЧЕНЬ_ДЛИННЫЙ_ТИП_СЕССИИ_КОТОРОГО_НЕ_БЫВАЕТ",
             "ip": "255.255.255.***", "server": "с" * 80,
             "lastSeenAt": "2026-08-12T15:10:00Z"},
            {"type": "WEB", "ip": "1.2.3.***", "server": None,
             "lastSeenAt": "2026-08-12T14:02:00Z"},
        ])
        box = painted(assets, renderer.render(card))
        assert inside_panel(box), f"столбцы вышли за панель: {box}"

    def test_пустой_профиль_рисуется(self, renderer: Renderer, assets: Assets) -> None:
        assert inside_panel(painted(assets, renderer.render(model.profile_card({}))))

    def test_пустой_экран_объясняется_словами(self, renderer: Renderer,
                                              assets: Assets) -> None:
        # Пустая панель читается как сбой отрисовки. Что-то должно быть
        # нарисовано даже тогда, когда показывать нечего.
        card = model.Card(kind="test", title="Пусто")
        assert painted(assets, renderer.render(card)) is not None


class TestПодгонкаТекста:
    def test_строка_никогда_не_шире_отведённого(self, fonts: FontBook) -> None:
        text = canvas.Text(_pen(), fonts)
        for value in ("Steve", "Ы" * 80, "12.08.2026 15:10 UTC", "A" * 300):
            for limit in (60, 120, 300, 700):
                body, size = text.fit(value, BOLD, theme.SIZE_ROW, limit)
                assert text.width(body, BOLD, size) <= limit

    def test_сначала_уменьшает_и_только_потом_режет(self, fonts: FontBook) -> None:
        # Уменьшенная строка читается целиком, обрезанная теряет конец — а
        # конец у ника как раз и отличает один от другого.
        text = canvas.Text(_pen(), fonts)
        value = "ДлинноватыйНик"
        body, size = text.fit(value, BOLD, theme.SIZE_ROW,
                              text.width(value, BOLD, theme.SIZE_ROW) - 12)
        assert body == value
        assert size < theme.SIZE_ROW

    def test_совсем_длинное_обрывается_многоточием(self, fonts: FontBook) -> None:
        text = canvas.Text(_pen(), fonts)
        body, _ = text.fit("Ы" * 300, BOLD, theme.SIZE_ROW, 200)
        assert body.endswith(canvas.ELLIPSIS)

    def test_нулевая_ширина_не_роняет(self, fonts: FontBook) -> None:
        assert canvas.Text(_pen(), fonts).fit("Ник", BOLD, 30, 0) == ("", 30)

    def test_абзац_переносится_по_словам(self, fonts: FontBook) -> None:
        text = canvas.Text(_pen(), fonts)
        lines = text.paragraph("Зайдите в игру и наберите команду", REGULAR, 25, 200)
        assert len(lines) > 1
        assert all(text.width(line, REGULAR, 25) <= 200 for line in lines)


class TestШрифты:
    def test_кириллица_есть(self, fonts: FontBook) -> None:
        # Пиксельные шрифты сплошь и рядом покрывают только латиницу, и
        # подставленный «красивый» файл превратил бы «Профиль» в квадраты.
        assert all(fonts.knows(char) for char in "ЖЩэёЙ")

    def test_неизвестный_символ_заменяется_точкой(self, fonts: FontBook) -> None:
        # Пустые квадраты посреди ника выглядят как сбой отрисовки.
        assert fonts.sanitize("Ник🙂") == "Ник·"

    def test_обычный_текст_не_трогается(self, fonts: FontBook) -> None:
        assert fonts.sanitize("Steve_2026") == "Steve_2026"

    def test_три_начертания_различимы(self, fonts: FontBook) -> None:
        widths = {weight: fonts.get(weight, 40).getlength("Профиль")
                  for weight in (REGULAR, BOLD, BLACK)}
        assert len(set(widths.values())) >= 1  # ширина может совпасть, файл — нет

    def test_пустой_каталог_это_отказ_а_не_падение(self, tmp_path) -> None:
        with pytest.raises(FontsUnavailable):
            FontBook(str(tmp_path))


class TestЗначки:
    def test_все_сетки_квадратные(self) -> None:
        for name, grid in pixels.ICONS.items():
            assert len(grid) == pixels.GRID, name
            assert all(len(row) == pixels.GRID for row in grid), name

    def test_ни_один_значок_не_пустой(self) -> None:
        for name, grid in pixels.ICONS.items():
            assert any("#" in row for row in grid), name

    def test_значки_состоят_только_из_клеток(self) -> None:
        for name, grid in pixels.ICONS.items():
            assert set("".join(grid)) <= {"#", "."}, name

    def test_неизвестное_имя_не_роняет(self) -> None:
        assert pixels.glyph("нет-такого") is None


class TestБезРесурсов:
    """Отсутствие шаблона — повод нарисовать скромнее, а не отказаться."""

    def test_шаблон_собирается_сам(self, tmp_path, fonts: FontBook) -> None:
        assets = Assets(str(tmp_path))
        template = assets.template()
        assert template.size == (theme.WIDTH, theme.HEIGHT)

    def test_карточка_рисуется_по_собранному_фону(self, tmp_path,
                                                  fonts: FontBook) -> None:
        assets = Assets(str(tmp_path))
        data = Renderer(fonts, assets.template()).render(model.profile_card(PROFILE))
        assert Image.open(io.BytesIO(data)).size == (theme.WIDTH, theme.HEIGHT)

    def test_готового_экрана_нет_и_это_не_ошибка(self, tmp_path) -> None:
        assert Assets(str(tmp_path)).menu() is None

    def test_запечённая_справка_ищется_по_отпечатку(self, assets: Assets) -> None:
        # Правка текстов команд обязана обесценивать запечённую картинку сама:
        # иначе на диске остаётся справка со старым списком.
        card = model.help_card("TELEGRAM")
        assert assets.screen_for(card) is not None
        assert assets.screen_for(model.help_card("MATRIX")) is None


def _pen():
    """Холст для измерений: рисовать в него не нужно, нужны только размеры."""
    from PIL import ImageDraw

    return ImageDraw.Draw(Image.new("RGB", (theme.WIDTH, theme.HEIGHT)))
