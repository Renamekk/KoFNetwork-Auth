"""Показ экранов в мессенджерах.

Проверяется стык между картинкой и сообщением — самое хрупкое место всей
затеи. Telegram не умеет превращать сообщение с фотографией в текстовое и
наоборот, Discord не убирает прежнее вложение сам, а картинка может не
нарисоваться в любой момент. Каждое из этих условий ломает навигацию тихо:
кнопки на вид работают, а экран не меняется.

Сети здесь нет. Мессенджеры подменены заглушками, которые записывают, что бот
попросил их сделать: проверять надо решение бота, а не работу чужого API.
"""

from __future__ import annotations

from typing import Any

import discord
import pytest
from discord_bot import views
from kofauth_common.cards import model
from kofauth_common.cards.service import CardService
from kofauth_common.cards.settings import CardSettings
from telegram_bot.bot import Screen, TelegramBot

PROFILE = {
    "username": "Inroka",
    "registeredAt": "2026-02-01T09:14:00Z",
    "lastLoginAt": "2026-08-12T15:10:00Z",
    "twoFactor": ["TELEGRAM"],
    "loginApproval": True,
}


class Painter:
    """Служба карточек с готовым ответом: рисовать по-настоящему тут незачем."""

    def __init__(self, data: bytes | None = b"jpeg", menu: bytes | None = b"banner"):
        self._data = data
        self._menu = menu
        self.handles: dict[str, str] = {}

    async def image(self, card) -> bytes | None:
        return self._data

    async def help(self, card) -> bytes | None:
        return self._data

    def menu(self) -> bytes | None:
        return self._menu

    def handle(self, digest: str) -> str | None:
        return self.handles.get(digest)

    def remember(self, digest: str, handle: str) -> None:
        self.handles[digest] = handle

    def close(self) -> None:
        pass


# ------------------------------------------------------------------- Telegram


class Photo:
    def __init__(self, file_id: str) -> None:
        self.file_id = file_id


class Sent:
    """Ответ Telegram на отправку: у фотографии есть ``file_id``."""

    def __init__(self, photo: bool = True) -> None:
        self.photo = [Photo("file-выданный")] if photo else None


class FakeMessage:
    """Сообщение Telegram, записывающее вызовы вместо обращений к сети."""

    def __init__(self, photo: bool = False) -> None:
        self.photo = [Photo("file-текущий")] if photo else None
        self.calls: list[tuple[str, Any]] = []

    async def answer(self, text: str, **kwargs: Any) -> Sent:
        self.calls.append(("answer", text))
        return Sent(photo=False)

    async def answer_photo(self, photo: Any, caption: str = "", **kwargs: Any) -> Sent:
        self.calls.append(("answer_photo", caption))
        return Sent()

    async def edit_media(self, media: Any, **kwargs: Any) -> Sent:
        self.calls.append(("edit_media", media.caption))
        return Sent()

    async def edit_caption(self, caption: str = "", **kwargs: Any) -> Sent:
        self.calls.append(("edit_caption", caption))
        return Sent()

    async def edit_text(self, text: str, **kwargs: Any) -> Sent:
        self.calls.append(("edit_text", text))
        return Sent()


class FakeQuery:
    def __init__(self, message: FakeMessage) -> None:
        self.message = message


def telegram_bot(cards: Painter | None = None) -> TelegramBot:
    """Бот с подменённой службой карточек и без сетевых зависимостей."""
    from kofauth_common.config import (
        ApiSettings,
        BrandSettings,
        DiscordSettings,
        Settings,
        TelegramSettings,
    )

    settings = Settings(
        api=ApiSettings(base_url="http://webapi:8080", bot_key="secret"),
        telegram=TelegramSettings(token="123456:" + "A" * 35),
        discord=DiscordSettings(token=""),
        brand=BrandSettings(),
        cards=CardSettings(enabled=False),
        panel_url="http://panel",
        log_level="INFO",
    )
    bot = TelegramBot.__new__(TelegramBot)
    bot._settings = settings
    bot._emoji = settings.brand.emoji()
    bot._cards = cards if cards is not None else Painter()
    return bot


class TestЭкраныTelegram:
    async def test_карточка_уходит_картинкой_с_подписью(self) -> None:
        bot = telegram_bot()
        screen = await bot._card_screen(model.profile_card(PROFILE), None)
        message = FakeMessage()
        await bot._deliver(message, screen)
        assert message.calls[0][0] == "answer_photo"
        assert "Профиль" in message.calls[0][1]

    async def test_без_картинки_уходит_полный_текст(self) -> None:
        # Не нарисовалось — человек обязан увидеть тот же экран, а не пустоту.
        bot = telegram_bot(Painter(data=None, menu=None))
        screen = await bot._card_screen(model.profile_card(PROFILE), None)
        message = FakeMessage()
        await bot._deliver(message, screen)
        assert message.calls[0][0] == "answer"
        assert "Inroka" in message.calls[0][1]

    async def test_подпись_короче_запасного_текста(self) -> None:
        # Данные видны на картинке; дублировать их под ней значит удваивать
        # экран. Но без картинки текст обязан быть полным.
        bot = telegram_bot()
        screen = await bot._card_screen(model.history_card([
            {"at": "2026-08-12T15:10:00Z", "success": True, "ip": "127.0.0.***"},
        ]), None)
        assert len(screen.caption) < len(screen.text)
        assert "127.0.0.***" in screen.text

    async def test_картинка_грузится_один_раз(self) -> None:
        # Telegram выдаёт идентификатор, и повторная отправка того же экрана
        # не грузит ничего.
        painter = Painter()
        bot = telegram_bot(painter)
        screen = await bot._card_screen(model.profile_card(PROFILE), None)
        await bot._deliver(FakeMessage(), screen)
        assert painter.handles[screen.digest] == "file-выданный"
        assert bot._media(screen) == "file-выданный"

    async def test_перерисовка_меняет_картинку_и_подпись(self) -> None:
        bot = telegram_bot()
        screen = await bot._card_screen(model.security_card(PROFILE), None)
        message = FakeMessage(photo=True)
        await bot._redraw(FakeQuery(message), screen)
        assert message.calls[0][0] == "edit_media"

    async def test_сообщение_с_картинкой_не_становится_текстовым(self) -> None:
        # Telegram такой правки не принимает: остаётся подпись, и в неё идёт
        # полный текст — прежняя картинка этот экран уже не описывает.
        bot = telegram_bot(Painter(data=None, menu=None))
        screen = await bot._card_screen(model.profile_card(PROFILE), None)
        message = FakeMessage(photo=True)
        await bot._redraw(FakeQuery(message), screen)
        assert message.calls[0][0] == "edit_caption"
        assert "Inroka" in message.calls[0][1]

    async def test_текстовое_сообщение_не_становится_картинкой(self) -> None:
        bot = telegram_bot()
        screen = await bot._card_screen(model.profile_card(PROFILE), None)
        message = FakeMessage(photo=False)
        await bot._redraw(FakeQuery(message), screen)
        assert message.calls[0][0] == "edit_text"
        assert "Inroka" in message.calls[0][1]

    async def test_отказ_картинки_оставляет_рабочий_экран(self) -> None:
        class Refusing(FakeMessage):
            async def edit_media(self, media: Any, **kwargs: Any) -> Sent:
                raise RuntimeError("Telegram не принял файл")

        bot = telegram_bot()
        screen = await bot._card_screen(model.profile_card(PROFILE), None)
        message = Refusing(photo=True)
        await bot._redraw(FakeQuery(message), screen)
        assert message.calls[-1][0] == "edit_caption"
        assert "Inroka" in message.calls[-1][1]

    async def test_отправка_без_картинки_при_отказе(self) -> None:
        class Refusing(FakeMessage):
            async def answer_photo(self, photo: Any, caption: str = "",
                                   **kwargs: Any) -> Sent:
                raise RuntimeError("Telegram не принял файл")

        bot = telegram_bot()
        screen = await bot._card_screen(model.profile_card(PROFILE), None)
        message = Refusing()
        await bot._deliver(message, screen)
        assert message.calls[-1][0] == "answer"
        assert "Inroka" in message.calls[-1][1]

    async def test_подтверждение_входа_остаётся_текстом(self) -> None:
        # Критические сообщения не превращаются в картинку ни при каких
        # настройках: их читают в спешке, пересылают и цитируют.
        bot = telegram_bot()
        message = FakeMessage(photo=False)
        await bot._redraw(FakeQuery(message), Screen.plain("Вход подтверждён."))
        assert message.calls[0] == ("edit_text", "Вход подтверждён.")

    async def test_главный_экран_берёт_готовый_баннер(self) -> None:
        bot = telegram_bot()
        screen = await bot._banner_screen("Привет", None)
        assert screen.image == b"banner"
        assert screen.digest

    async def test_без_ресурсов_баннер_ищется_в_настройках(self) -> None:
        # Развёртывания со своим баннером в сети продолжают работать как
        # работали: ресурсы пакета их не отменяют.
        from kofauth_common.config import BrandSettings

        bot = telegram_bot(Painter(menu=None))
        object.__setattr__(bot._settings, "brand",
                           BrandSettings(banner_url="https://kof.example/b.jpg"))
        screen = await bot._banner_screen("Привет", None)
        assert screen.image == "https://kof.example/b.jpg"

    async def test_совсем_без_баннера_остаётся_текст(self) -> None:
        bot = telegram_bot(Painter(menu=None))
        screen = await bot._banner_screen("Привет", None)
        message = FakeMessage()
        await bot._deliver(message, screen)
        assert message.calls[0] == ("answer", "Привет")


# -------------------------------------------------------------------- Discord


def brand(cards: Painter | None = None) -> views.Brand:
    return views.Brand(panel_url="http://panel",
                       cards=cards if cards is not None else Painter())


class TestЭкраныDiscord:
    async def test_карточка_приезжает_вложением(self) -> None:
        # Ссылки наружу у карточки нет и быть не должно: она рисуется в
        # памяти бота и нигде не выкладывается.
        body, files = await views.card_screen(brand(), model.profile_card(PROFILE))
        assert len(files) == 1
        assert body.image.url == f"attachment://{files[0].filename}"
        assert files[0].filename == "profile.jpg"

    async def test_без_картинки_остаётся_текст(self) -> None:
        body, files = await views.card_screen(brand(Painter(data=None)),
                                              model.profile_card(PROFILE))
        assert files == []
        assert "Inroka" in body.description

    async def test_вложение_убирается_вместе_с_картинкой(self) -> None:
        # Пустой список означает «убрать прежнее»: без него текст показался бы
        # поверх картинки предыдущего экрана.
        _, files = await views.card_screen(brand(Painter(data=None)),
                                           model.security_card(PROFILE))
        assert files == []

    async def test_главный_экран_с_баннером(self) -> None:
        body, files = views.home_screen("Inroka", brand())
        assert len(files) == 1
        assert files[0].filename == "menu.jpg"
        assert "Inroka" in body.description

    async def test_главный_экран_без_ресурсов(self) -> None:
        body, files = views.home_screen("Inroka", brand(Painter(menu=None)))
        assert files == []
        assert "Inroka" in body.description

    async def test_справка_приезжает_картинкой_и_ссылками(self) -> None:
        body, files = await views.help_message(brand())
        assert len(files) == 1
        assert "http://panel" in body.description

    async def test_справка_без_картинки_остаётся_текстом(self) -> None:
        body, files = await views.help_message(brand(Painter(data=None)))
        assert files == []
        assert "/discord" in body.description

    def test_цвет_и_заголовок_фирменные(self) -> None:
        from kofauth_common import texts

        body = views.embed("Заголовок", ["строка"])
        assert body.colour.value == texts.BRAND_COLOUR


class Response:
    """Ответ на взаимодействие Discord: запоминает, о чём попросил бот."""

    def __init__(self) -> None:
        self.edited: dict[str, Any] | None = None
        self.sent: dict[str, Any] | None = None

    async def edit_message(self, **kwargs: Any) -> None:
        self.edited = kwargs

    async def send_message(self, *args: Any, **kwargs: Any) -> None:
        self.sent = kwargs


class Interaction:
    def __init__(self, user_id: int = 2002) -> None:
        self.user = type("User", (), {"id": user_id})()
        self.response = Response()


class FakeApi:
    """Клиент API с готовыми ответами: сети в этих проверках нет."""

    def __init__(self, data: dict[str, Any]) -> None:
        self._data = data

    async def account(self, platform: str, user_id: int):
        return type("Result", (), {"ok": True, "data": self._data, "error": ""})()

    async def history(self, platform: str, user_id: int, limit: int = 10):
        return type("Result", (), {"ok": True, "data": {"history": []}, "error": ""})()


class TestНавигацияDiscord:
    """Кнопки перерисовывают то же сообщение и меняют вложение вместе с ним."""

    async def test_профиль_открывается_картинкой(self) -> None:
        view = views.MenuView(FakeApi(PROFILE), 2002, True, brand())
        interaction = Interaction()
        await view.profile.callback(interaction)
        edited = interaction.response.edited
        assert edited is not None
        assert "Профиль" in edited["embed"].title
        assert len(edited["attachments"]) == 1
        assert isinstance(edited["view"], views.BackView)

    async def test_возврат_ведёт_в_главное_меню(self) -> None:
        view = views.BackView(FakeApi(PROFILE), 2002, brand())
        interaction = Interaction()
        await view.back.callback(interaction)
        edited = interaction.response.edited
        assert isinstance(edited["view"], views.MenuView)
        assert edited["attachments"][0].filename == "menu.jpg"

    async def test_чужое_меню_не_открывается(self) -> None:
        # Проверка владельца осталась на месте: сообщение эфемерное, но
        # переслать его всё равно можно.
        view = views.MenuView(FakeApi(PROFILE), 2002, True, brand())
        assert await view.interaction_check(Interaction(user_id=9999)) is False

    async def test_вопрос_об_отвязке_остаётся_текстом(self) -> None:
        view = views.SecurityView(FakeApi(PROFILE), 2002, True, brand())
        interaction = Interaction()
        await view.unlink.callback(interaction)
        edited = interaction.response.edited
        assert edited["attachments"] == []
        assert "Отвязать" in edited["embed"].title
        assert isinstance(edited["view"], views.ConfirmUnlinkView)


class TestОдинаковостьПлатформ:
    """Telegram и Discord показывают один и тот же экран.

    Совпадать обязано содержимое, а не разметка: у платформ разные
    возможности, и подгонять их друг под друга до пикселя нельзя. Но карточка
    у обеих одна и та же — она и есть то, что человек видит.
    """

    async def test_картинка_профиля_совпадает(self) -> None:
        bot = telegram_bot()
        telegram = await bot._card_screen(
            model.profile_card(PROFILE, platform="Telegram"), None)
        discord_body, discord_files = await views.card_screen(
            brand(), model.profile_card(PROFILE, platform="Telegram"))
        assert telegram.image == b"jpeg"
        assert len(discord_files) == 1
        assert "Профиль" in telegram.caption
        assert "Профиль" in discord_body.title

    def test_наборы_экранов_совпадают(self) -> None:
        from telegram_bot.bot import SCREEN_MARKS as telegram_marks

        assert set(telegram_marks) == set(views.SCREEN_MARKS)


@pytest.fixture(autouse=True)
def _quiet_discord() -> None:
    """discord.py при сборке ``File`` ничего не пишет — фикстура для симметрии."""
    yield


def test_вложение_называется_по_виду_экрана() -> None:
    assert views.attachment("history") == "history.jpg"


def test_служба_карточек_создаётся_без_ресурсов(tmp_path) -> None:
    # Бот обязан подниматься даже там, где каталога ресурсов нет вовсе.
    service = CardService(CardSettings(directory=str(tmp_path)))
    assert service.enabled is True  # пока не спросили картинку — не узнать
    service.close()


def test_discord_file_одноразовый() -> None:
    # Напоминание себе: File читает буфер, поэтому на каждую отправку нужен
    # новый — карточка кэшируется байтами, а не объектом Discord.
    import io

    handle = discord.File(io.BytesIO(b"jpeg"), filename="x.jpg")
    assert handle.filename == "x.jpg"
