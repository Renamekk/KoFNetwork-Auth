"""Меню Discord-бота.

Навигация построена на ``discord.ui.View``: одно эфемерное сообщение, которое
перерисовывается кнопками. Эфемерное — принципиально: профиль, устройства и
история видны только вызвавшему, даже если команда набрана в общем канале.

Отдельная сложность Discord — время жизни View. Компоненты перестают работать
через ``timeout`` секунд, после чего нажатие возвращает ошибку. Поэтому у
меню таймаут задан явно и по его истечении кнопки гасятся, а не остаются
выглядящими рабочими.

Оформление
----------

Стилей у кнопок Discord ровно четыре, и три из них — чужие цвета. Палитра сети
чёрно-бело-красная, поэтому используются только два:

* :data:`ACCENT` (``danger``) — фирменный красный ``#FF2D2D``. Им помечено то,
  ради чего экран открыт, и он же достаётся опасному действию: другого
  красного у Discord нет.
* :data:`NEUTRAL` (``secondary``) — ближайший к белому нейтральный. Обычная
  навигация, возврат и отмена.

``primary`` (синий) и ``success`` (зелёный) не используются нигде. Четыре
разных цвета в одном меню читаются как четыре разных приложения, а не как одно.

Опасное действие отличается от главного не цветом, а тремя вещами сразу:
значком открытого замка, отдельной строкой в раскладке и обязательным
подтверждением на следующем экране. Возврат — всегда последняя строка и всегда
со стрелкой.

Фирменные emoji ставятся здесь же, при сборке экрана: декоратор выполняется
один раз при загрузке модуля, а набор значков приходит из настроек.

Картинки экранов
----------------

Разделы показываются картинкой внутри эмбеда, кнопки под ней остаются родными
кнопками Discord. Картинка приезжает вложением и подставляется в эмбед по
``attachment://``: ссылки наружу для неё нет и быть не должно — карточка
рисуется в памяти бота и нигде не выкладывается.

**Картинка нигде не единственный носитель смысла.** Подтверждение входа,
предупреждения и ошибки остаются текстом. У разделов вместе с картинкой
приезжает короткое описание, а если нарисовать её не вышло — тот же экран
обычным текстом, ровно как до картинок.
"""

from __future__ import annotations

import io
import logging
from collections.abc import Awaitable, Callable
from typing import Any

import discord
from kofauth_common import ApiUnavailable, Card, CardService, KoFAuthApi, cards, texts
from kofauth_common.glyphs import DEFAULT, EmojiSet

LOGGER = logging.getLogger(__name__)

PLATFORM = "DISCORD"

#: Как площадка называется для человека — подпись в подвале карточки профиля.
PLATFORM_NAME = "Discord"

#: Значок в заголовке эмбеда по виду карточки: тот же, что и на кнопке,
#: которая на этот экран ведёт.
SCREEN_MARKS = {
    cards.PROFILE: "profile",
    cards.SECURITY: "security",
    cards.HISTORY: "history",
    cards.SESSIONS: "sessions",
    cards.HELP: "help",
}

#: Заголовки эмбедов. У карточки заголовок свой, короткий: он набран крупно и
#: стоит по центру, а в строке эмбеда место есть и уточнение уместно.
SCREEN_TITLES = {
    cards.PROFILE: "Профиль",
    cards.SECURITY: "Защита аккаунта",
    cards.HISTORY: "Последние входы",
    cards.SESSIONS: "Активные сессии",
    cards.HELP: "Справка",
}

#: Столько живёт меню. Больше десяти минут Discord не гарантирует доставку
#: взаимодействия, меньше — человек не успевает прочитать историю входов.
MENU_TIMEOUT = 300.0

#: Фирменный красный. У Discord он же и «опасно» — второго красного нет.
ACCENT = discord.ButtonStyle.danger

#: Нейтральный, ближайший аналог белого: навигация, возврат, отмена.
NEUTRAL = discord.ButtonStyle.secondary

#: То же, что :data:`ACCENT`. Отдельное имя нужно, чтобы в коде было видно,
#: где красный означает «главное», а где — «опасно»: цвет один, смысл разный,
#: и различает их значок с раскладкой, а не стиль кнопки.
DESTRUCTIVE = discord.ButtonStyle.danger


def apply_glyphs(view: discord.ui.View, emoji: EmojiSet, glyphs: dict[str, str]) -> None:
    """Ставит кнопкам экрана значки из набора.

    :param glyphs: имя кнопки в классе → ключ значка. Проходить по
        ``view.children`` нельзя: там нет ничего, по чему опознать кнопку, а
        порядок меняется вместе с раскладкой.
    """
    for attribute, key in glyphs.items():
        getattr(view, attribute).emoji = emoji.discord(key)


def embed(
    title: str,
    lines: list[str],
    colour: int = texts.BRAND_COLOUR,
    *,
    banner: str = "",
) -> discord.Embed:
    """Единое оформление экранов.

    Цвет полосы — фирменный красный KoF: это единственное место, где фирменный
    цвет в Discord вообще доступен, разметка сообщений цветов не знает.

    :param banner: ссылка на картинку шапки. Ставится только на главный экран:
        на каждом подэкране она вытеснила бы содержимое за пределы видимого
        и превратила навигацию в пролистывание.
    """
    result = discord.Embed(
        title=title, description="\n".join(lines), colour=discord.Colour(colour)
    )
    if banner:
        result.set_image(url=banner)
    return result


class Brand:
    """Оформление, общее для всех экранов.

    Собрано в один объект, чтобы каждое представление не таскало по отдельности
    ссылку на кабинет, баннер, адрес доната и набор значков: их приходится
    передавать через всю цепочку экранов, и четвёртый параметр подряд неизбежно
    теряется в одной из веток «назад».
    """

    __slots__ = ("panel_url", "banner_url", "donate_url", "emoji", "cards")

    def __init__(self, panel_url: str, banner_url: str = "", donate_url: str = "",
                 emoji: EmojiSet = DEFAULT, cards: CardService | None = None) -> None:
        self.panel_url = panel_url
        self.banner_url = banner_url
        self.donate_url = donate_url
        #: Значки кнопок: фирменные, если сеть их завела, иначе обычные.
        self.emoji = emoji
        #: Картинки экранов. Своя на бота: в ней кэш готовых карточек, а он
        #: живёт столько же, сколько процесс.
        self.cards = cards if cards is not None else CardService()


async def call(interaction: discord.Interaction, awaitable: Awaitable[Any]) -> Any | None:
    """Запрос к API из обработчика кнопки.

    Недоступность API — обычное дело: WebAPI перезапускают, сеть моргает. Без
    этой обёртки исключение уходило из обработчика наружу, discord.py показывал
    «This interaction failed», и человек оставался перед экраном, кнопки
    которого выглядят рабочими и молчат. Отдельным сообщением он хотя бы
    понимает, что произошло, и может нажать ещё раз: сам экран цел.

    :return: ответ API либо ``None``, если ответа нет
    """
    try:
        return await awaitable
    except ApiUnavailable:
        await interaction.response.send_message(texts.API_UNAVAILABLE, ephemeral=True)
        return None


def attachment(name: str) -> str:
    """Имя вложения с картинкой экрана.

    Оно же попадает в ссылку ``attachment://`` внутри эмбеда, поэтому имя
    собирается в одном месте: разойдись эти два написания — и Discord покажет
    эмбед без картинки, ничего при этом не сообщив.
    """
    return f"{name}.jpg"


async def card_screen(brand: Brand, card: Card) -> tuple[discord.Embed, list[discord.File]]:
    """Эмбед с картинкой и вложение к нему.

    Не нарисовалось — эмбед собирается тем же текстом, что был до картинок, и
    вложений не будет. Для вызывающего разница только в списке файлов, и он
    передаёт его в Discord как есть: пустой список означает «убрать прежнее
    вложение», что при переходе с картинки на текст как раз и нужно.
    """
    title = SCREEN_TITLES.get(card.kind, card.title)
    mark = brand.emoji.discord(SCREEN_MARKS.get(card.kind, "profile"))
    image = await brand.cards.image(card)
    if image is None:
        return embed(f"{mark} {title}", list(card.text)), []

    name = attachment(card.kind)
    result = embed(f"{mark} {title}", list(card.caption))
    result.set_image(url=f"attachment://{name}")
    return result, [discord.File(io.BytesIO(image), filename=name)]


class MenuView(discord.ui.View):
    """Главное меню.

    Раздела «Устройства» здесь больше нет: он показывал те же адреса, что и
    «История», только в другом порядке, и ни одного действия к ним не
    прилагалось.

    :param user_id: кому принадлежит меню; чужие нажатия отклоняются
    """

    #: Красным помечены два входа, ради которых меню и открывают; история и
    #: сессии — обычная навигация и потому нейтральные.
    GLYPHS = {"profile": "profile", "security": "security",
              "history": "history", "sessions": "sessions"}

    def __init__(self, api: KoFAuthApi, user_id: int, linked: bool,
                 brand: Brand) -> None:
        super().__init__(timeout=MENU_TIMEOUT)
        self._api = api
        self._user_id = user_id
        self._brand = brand
        apply_glyphs(self, brand.emoji, self.GLYPHS)
        if not linked:
            # Непривязанному человеку остальные кнопки ответят одним и тем же
            # «аккаунт не привязан» — показывать их незачем.
            self.clear_items()
            self.add_item(HelpButton(brand))
        if brand.donate_url:
            # Кнопка-ссылка: у неё свой стиль, других Discord для ссылок не
            # даёт. Фирменность держится на красном сердце и на том, что она
            # всегда стоит последней строкой.
            self.add_item(discord.ui.Button(
                label="Поддержать сервер", emoji=brand.emoji.discord("donate"),
                style=discord.ButtonStyle.link, url=brand.donate_url, row=2,
            ))

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        """Чужое меню нажимать нельзя.

        Сообщение эфемерное, но проверка всё равно нужна: она стоит дёшево,
        а её отсутствие — ровно та ошибка, из-за которой в чужом канале
        показывают чужой профиль.
        """
        if interaction.user.id == self._user_id:
            return True
        await interaction.response.send_message(
            "Это меню открыл другой человек. Наберите /menu.", ephemeral=True
        )
        return False

    async def on_timeout(self) -> None:
        for item in self.children:
            item.disabled = True

    # ------------------------------------------------------------------ кнопки

    @discord.ui.button(label="Профиль", emoji=texts.ICON["profile"], style=ACCENT)
    async def profile(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        await self._screen(
            interaction, self._api.account(PLATFORM, self._user_id),
            lambda data: cards.profile_card(data, donate_url=self._brand.donate_url,
                                            platform=PLATFORM_NAME),
        )

    @discord.ui.button(label="Защита", emoji=texts.ICON["security"], style=ACCENT)
    async def security(self, interaction: discord.Interaction,
                       button: discord.ui.Button) -> None:
        result = await self._fetch(interaction, self._api.account(PLATFORM, self._user_id))
        if result is None:
            return
        await show(interaction, self._brand, cards.security_card(result),
                   SecurityView(self._api, self._user_id,
                                bool(result.get("loginApproval")), self._brand))

    @discord.ui.button(label="История", emoji=texts.ICON["history"], style=NEUTRAL)
    async def history(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        await self._screen(
            interaction,
            self._api.history(PLATFORM, self._user_id, limit=texts.HISTORY_LIMIT),
            lambda data: cards.history_card(data.get("history", [])),
        )

    @discord.ui.button(label="Сессии", emoji=texts.ICON["sessions"], style=NEUTRAL)
    async def sessions(self, interaction: discord.Interaction,
                       button: discord.ui.Button) -> None:
        await self._screen(interaction, self._api.sessions(PLATFORM, self._user_id),
                           lambda data: cards.sessions_card(data.get("sessions", [])))

    # ------------------------------------------------------------------ общее

    async def _screen(self, interaction: discord.Interaction, awaitable: Awaitable[Any],
                      build: Callable[[dict[str, Any]], Card]) -> None:
        result = await self._fetch(interaction, awaitable)
        if result is None:
            return
        await show(interaction, self._brand, build(result),
                   BackView(self._api, self._user_id, self._brand))

    async def _fetch(self, interaction: discord.Interaction,
                     awaitable: Awaitable[Any]) -> dict[str, Any] | None:
        """Выполняет запрос, отвечая на отказы понятным текстом."""
        result = await call(interaction, awaitable)
        if result is None:
            return None
        if not result.ok:
            await interaction.response.send_message(
                texts.NOT_LINKED.format(command="/discord")
                if result.error == "NOT_LINKED"
                else texts.describe_error(result.error),
                ephemeral=True,
            )
            return None
        return result.data


def home_lines(username: str | None) -> list[str]:
    """Содержимое главного экрана."""
    if username:
        return [
            f"{texts.ICON['profile']} Аккаунт: **{username}**",
            "",
            "Выберите раздел.",
        ]
    return [
        "Аккаунт не привязан.",
        "",
        f"{texts.ICON['link']} Возьмите код в игре командой `/discord` "
        "и пришлите мне `/link КОД`.",
    ]


def home_screen(username: str | None, brand: Brand,
                lines: list[str] | None = None) -> tuple[discord.Embed, list[discord.File]]:
    """Главный экран: готовый баннер сети и короткая подпись под ним.

    Баннер берётся из ресурсов пакета и приезжает вложением. Ссылка из
    настроек остаётся запасным путём: развёртывания, у которых свой баннер
    лежит в сети, продолжают работать как работали.

    :param lines: чем заменить обычное содержимое — например, ответом об
        отвязке. Экран при этом остаётся тем же самым, меняется только текст.
    """
    body = home_lines(username) if lines is None else lines
    data = brand.cards.menu()
    if data is None:
        return embed(texts.BRAND_TITLE, body, banner=brand.banner_url), []
    name = attachment("menu")
    result = embed(texts.BRAND_TITLE, body)
    result.set_image(url=f"attachment://{name}")
    return result, [discord.File(io.BytesIO(data), filename=name)]


async def show(interaction: discord.Interaction, brand: Brand, card: Card,
               view: discord.ui.View | None) -> None:
    """Перерисовывает сообщение под карточку.

    Вложения передаются всегда — и когда картинка есть, и когда её нет. Это
    не формальность: пустой список означает «убрать прежнее вложение», и без
    него экран, у которого картинка не получилась, показывал бы текст поверх
    картинки предыдущего экрана.
    """
    body, files = await card_screen(brand, card)
    await interaction.response.edit_message(embed=body, view=view, attachments=files)


async def go_home(interaction: discord.Interaction, api: KoFAuthApi, user_id: int,
                  brand: Brand, username: str | None, linked: bool,
                  lines: list[str] | None = None) -> None:
    """Возврат в главное меню — одинаково из любого экрана."""
    body, files = home_screen(username, brand, lines)
    await interaction.response.edit_message(
        embed=body, view=MenuView(api, user_id, linked, brand), attachments=files,
    )


class BackView(discord.ui.View):
    """Экран с одной кнопкой возврата.

    Возврат нейтральный и со стрелкой — на всех экранах одинаково. Красным его
    не помечают: уводить акцент на выход из экрана значит спорить с тем, ради
    чего экран открыт.
    """

    GLYPHS = {"back": "back"}

    def __init__(self, api: KoFAuthApi, user_id: int, brand: Brand) -> None:
        super().__init__(timeout=MENU_TIMEOUT)
        self._api = api
        self._user_id = user_id
        self._brand = brand
        apply_glyphs(self, brand.emoji, self.GLYPHS)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        return interaction.user.id == self._user_id

    @discord.ui.button(label="Назад", emoji=texts.ICON["back"], style=NEUTRAL)
    async def back(self, interaction: discord.Interaction,
                   button: discord.ui.Button) -> None:
        result = await call(interaction, self._api.account(PLATFORM, self._user_id))
        if result is None:
            return
        linked = result.ok
        await go_home(interaction, self._api, self._user_id, self._brand,
                      result.data.get("username") if linked else None, linked)


class SecurityView(discord.ui.View):
    """Экран защиты: переключатель, обычное действие и опасное.

    Раскладка идёт сверху вниз от главного к опасному, и отвязка стоит
    отдельной строкой. Это не украшение: красный у Discord один на «главное» и
    на «опасно», и в общей строке они бы слились, а промах по соседней кнопке
    попадал бы в единственное действие, которое выключает второй фактор.
    """

    GLYPHS = {"logout": "logout", "unlink": "unlink", "back": "back"}

    def __init__(self, api: KoFAuthApi, user_id: int, login_approval: bool,
                 brand: Brand) -> None:
        super().__init__(timeout=MENU_TIMEOUT)
        self._api = api
        self._user_id = user_id
        self._brand = brand
        self._login_approval = login_approval
        apply_glyphs(self, brand.emoji, self.GLYPHS)

        # Надпись описывает действие, а не состояние: «Выключить» рядом со
        # словом «включено» читается однозначно.
        self.toggle.label = (
            "Выключить подтверждение" if login_approval else "Включить подтверждение"
        )
        self.toggle.emoji = brand.emoji.discord(
            "bell_off" if login_approval else "bell_on"
        )

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        return interaction.user.id == self._user_id

    @discord.ui.button(style=ACCENT)
    async def toggle(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        result = await call(interaction, self._api.set_login_approval(
            PLATFORM, self._user_id, not self._login_approval
        ))
        if result is None:
            return
        if not result.ok:
            await interaction.response.send_message(
                texts.describe_error(result.error), ephemeral=True
            )
            return
        await show(interaction, self._brand, cards.security_card(result.data),
                   SecurityView(self._api, self._user_id,
                                bool(result.data.get("loginApproval")), self._brand))

    @discord.ui.button(label="Выйти со всех устройств", emoji=texts.ICON["logout"],
                       style=NEUTRAL)
    async def logout(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        result = await call(interaction, self._api.logout_all(PLATFORM, self._user_id))
        if result is None:
            return
        await interaction.response.send_message(
            f"{texts.ICON['ok']} Завершено сессий: {result.data.get('revoked', 0)}"
            if result.ok else texts.describe_error(result.error),
            ephemeral=True,
        )

    @discord.ui.button(label="Отвязать Discord", emoji=texts.ICON["unlink"],
                       style=DESTRUCTIVE, row=1)
    async def unlink(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        # Отвязка выключает второй фактор и уведомления разом, то есть тихо
        # снижает защиту аккаунта. Один промах по кнопке не должен её выполнить.
        #
        # Вопрос задаётся текстом и без картинки: предупреждение, набранное
        # внутри изображения, нельзя ни процитировать, ни прочитать голосом.
        await interaction.response.edit_message(
            embed=embed(
                f"{texts.ICON['warning']} Отвязать Discord?",
                ["Подтверждение входа и уведомления перестанут работать."],
                colour=texts.DANGER_COLOUR,
            ),
            view=ConfirmUnlinkView(self._api, self._user_id, self._brand),
            attachments=[],
        )

    @discord.ui.button(label="Назад", emoji=texts.ICON["back"], style=NEUTRAL, row=2)
    async def back(self, interaction: discord.Interaction,
                   button: discord.ui.Button) -> None:
        result = await call(interaction, self._api.account(PLATFORM, self._user_id))
        if result is None:
            return
        await go_home(interaction, self._api, self._user_id, self._brand,
                      result.data.get("username") if result.ok else None, result.ok)


class ConfirmUnlinkView(discord.ui.View):
    """Подтверждение отвязки.

    Слева согласие, справа отмена — тот же порядок, что и у подтверждения
    входа: привычка не должна подводить именно там, где отменяют защиту.
    """

    GLYPHS = {"confirm": "unlink", "cancel": "back"}

    def __init__(self, api: KoFAuthApi, user_id: int, brand: Brand) -> None:
        super().__init__(timeout=60.0)
        self._api = api
        self._user_id = user_id
        self._brand = brand
        apply_glyphs(self, brand.emoji, self.GLYPHS)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        return interaction.user.id == self._user_id

    @discord.ui.button(label="Да, отвязать", emoji=texts.ICON["unlink"],
                       style=DESTRUCTIVE)
    async def confirm(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        result = await call(interaction, self._api.unlink(PLATFORM, self._user_id))
        if result is None:
            return
        if not result.ok:
            await interaction.response.edit_message(
                embed=embed(texts.BRAND_TITLE, [texts.describe_error(result.error)],
                            colour=texts.DANGER_COLOUR),
                view=MenuView(self._api, self._user_id, True, self._brand),
                attachments=[],
            )
            return
        await go_home(interaction, self._api, self._user_id, self._brand, None, False)

    @discord.ui.button(label="Отмена", emoji=texts.ICON["back"], style=NEUTRAL)
    async def cancel(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        result = await call(interaction, self._api.account(PLATFORM, self._user_id))
        if result is None:
            return
        if not result.ok:
            # Ответ об отказе — не профиль: строить из него карточку «Защита»
            # значило бы показать человеку экран, где всё выключено и ничего
            # не привязано, хотя на деле мы просто не спросили.
            await go_home(interaction, self._api, self._user_id, self._brand, None, False)
            return
        await show(interaction, self._brand, cards.security_card(result.data),
                   SecurityView(self._api, self._user_id,
                                bool(result.data.get("loginApproval")), self._brand))


class HelpButton(discord.ui.Button):
    """Кнопка справки для непривязанного человека.

    Единственное осмысленное действие на экране — значит, красная: акцент
    ставится на том, ради чего экран открыт.
    """

    def __init__(self, brand: Brand) -> None:
        super().__init__(label="Как привязать аккаунт",
                         emoji=brand.emoji.discord("link"), style=ACCENT)
        self._brand = brand

    async def callback(self, interaction: discord.Interaction) -> None:
        await help_screen(interaction, self._brand, view=None)


def help_lines(brand: Brand) -> list[str]:
    """Полная справка текстом — она же запасной вид карточки справки."""
    lines = [
        "1. Зайдите в игру",
        "2. Наберите `/discord`",
        "3. Перейдите по ссылке в канал привязки",
        "4. Пришлите мне `/link КОД`",
        "",
        "Код выдаётся только в игре — это единственный способ доказать, "
        "что аккаунт ваш.",
        "",
        f"{texts.ICON['site']} Личный кабинет: {brand.panel_url}",
    ]
    if brand.donate_url:
        lines.append(f"{texts.ICON['donate']} Поддержать сервер: {brand.donate_url}")
    return lines


async def help_message(brand: Brand) -> tuple[discord.Embed, list[discord.File]]:
    """Справка: запечённая картинка и ссылки под ней.

    Ссылки остаются текстом, а не уезжают в изображение: адрес кабинета
    задаётся развёртыванием, в сообщении он нажимается, а с картинки его
    пришлось бы переписывать руками. По той же причине их нет и на самой
    карточке — она лежит в репозитории и адреса конкретной сети не знает.
    """
    card = cards.help_card(PLATFORM)
    image = await brand.cards.help(card)
    if image is None:
        return embed(f"{texts.ICON['link']} Как привязать аккаунт", help_lines(brand)), []

    name = attachment(card.kind)
    body = embed(f"{texts.ICON['help']} Справка", [
        f"{texts.ICON['site']} Личный кабинет: {brand.panel_url}",
        *([f"{texts.ICON['donate']} Поддержать сервер: {brand.donate_url}"]
          if brand.donate_url else []),
    ])
    body.set_image(url=f"attachment://{name}")
    return body, [discord.File(io.BytesIO(image), filename=name)]


async def help_screen(interaction: discord.Interaction, brand: Brand,
                      view: discord.ui.View | None) -> None:
    """Перерисовывает сообщение под справку."""
    body, files = await help_message(brand)
    await interaction.response.edit_message(embed=body, view=view, attachments=files)


class ApprovalView(discord.ui.View):
    """Кнопки подтверждения входа.

    Ровно две и никаких кодов. Идентификатор запроса хранится в самом объекте
    представления, но решение принимает сервер: он же и сверяет, что нажал тот,
    кому кнопка адресована.

    Проверка владельца выполняется дважды — здесь и на сервере. Здешняя
    проверка нужна, чтобы человек сразу получил внятный ответ вместо отказа
    без объяснений; серверная — потому что клиентской доверять нельзя, а
    сообщение с кнопками можно переслать.

    Таймаут совпадает со сроком жизни запроса: держать нажимаемыми кнопки,
    за которыми уже нет действующего запроса, — значит обещать несбыточное.

    Оформление здесь важнее, чем на любом другом экране: два ответа
    противоположны по смыслу, и спутать их нельзя. Красная «Войти» — то, ради
    чего сообщение пришло; «Отклонить» нейтральная, но с красным крестом,
    поэтому отказ остаётся заметным и на беглый взгляд, и на ощупь. Зелёной
    кнопки здесь нет намеренно: третий цвет в паре не добавляет различимости,
    зато выбивается из палитры сети.
    """

    GLYPHS = {"approve": "approve", "deny": "deny"}

    def __init__(self, api: KoFAuthApi, approval_id: str, recipient_id: int,
                 timeout: float = 120.0, emoji: EmojiSet = DEFAULT) -> None:
        super().__init__(timeout=timeout)
        self._api = api
        self._approval_id = approval_id
        self._recipient_id = recipient_id
        apply_glyphs(self, emoji, self.GLYPHS)

    @discord.ui.button(label="Войти", emoji=texts.ICON["approve"], style=ACCENT)
    async def approve(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        await self._decide(interaction, approved=True)

    @discord.ui.button(label="Отклонить", emoji=texts.ICON["deny"], style=NEUTRAL)
    async def deny(self, interaction: discord.Interaction,
                   button: discord.ui.Button) -> None:
        await self._decide(interaction, approved=False)

    async def _decide(self, interaction: discord.Interaction, approved: bool) -> None:
        if interaction.user.id != self._recipient_id:
            await interaction.response.send_message(
                "Эта кнопка адресована другому человеку.", ephemeral=True
            )
            return

        try:
            result = await self._api.approve(
                PLATFORM, interaction.user.id, self._approval_id, approved
            )
        except ApiUnavailable:
            # Сообщение не правим: запрос, возможно, ещё жив, и человек сможет
            # нажать снова.
            await interaction.response.send_message(texts.API_UNAVAILABLE, ephemeral=True)
            return

        outcome = str(result.data.get("result") or "")
        text = approval_outcome_text(outcome, approved)

        if outcome == "FOREIGN":
            await interaction.response.send_message(text, ephemeral=True)
            return

        if outcome not in DECIDED:
            # Сервер решения не записал: неверный ключ бота, отвергнутый запрос,
            # незнакомый ответ. Кнопки в этом случае остаются — запрос, возможно,
            # ещё жив, и убрать их значило бы отнять единственный способ войти,
            # ничего взамен не дав.
            await interaction.response.send_message(text, ephemeral=True)
            return

        # Кнопки убираем: решение принято, нажимать больше нечего.
        await interaction.response.edit_message(
            embed=embed(f"{texts.ICON['lock']} Подтверждение входа", [text],
                        colour=texts.BRAND_COLOUR if approved else texts.DANGER_COLOUR),
            view=None,
        )


#: Исходы, после которых запрос действительно закрыт. Только они позволяют
#: убрать кнопки: всё остальное — «сервер решения не принял», и человек обязан
#: сохранить возможность нажать снова.
DECIDED = frozenset({"APPLIED", "ALREADY_DECIDED", "EXPIRED", "NOT_FOUND"})


def approval_outcome_text(outcome: str, approved: bool) -> str:
    """Человеческое описание исхода нажатия."""
    if outcome == "APPLIED":
        return (
            "Вход подтверждён." if approved
            else "Вход отклонён. Если это были не вы — смените пароль."
        )
    if outcome == "ALREADY_DECIDED":
        return "Этот запрос уже обработан."
    if outcome == "EXPIRED":
        return "Время подтверждения истекло. Зайдите в игру ещё раз."
    if outcome == "FOREIGN":
        return "Эта кнопка адресована другому человеку."
    if outcome == "NOT_FOUND":
        return "Запрос не найден: возможно, он уже устарел."
    return "Не получилось обработать нажатие."
