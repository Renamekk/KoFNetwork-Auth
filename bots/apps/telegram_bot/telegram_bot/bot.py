"""Telegram-бот KoF Network.

Направление привязки то же, что и везде в системе: код выдаётся в игре и
вводится здесь. Обратный порядок позволил бы привязать свой Telegram к чужому
нику — достаточно было бы знать ник.

Бот тонкий: он не знает ни паролей, ни устройства базы. Всё, что он делает,
проходит через ``/api/bot``; оттуда же он читает очередь сообщений.

Подтверждение входа — это две кнопки и ничего больше. Кодов бот не выдаёт и
вводить в Minecraft ничего не просит: код был предъявительским, его можно было
получить без всякой попытки входа и предъявить когда угодно, а проверить, тот
ли человек его предъявил, было нечем.

Экран и картинка
----------------

Разделы — картинки с родными кнопками под ними: главное меню и справка берутся
готовыми из ресурсов, профиль, защита, история и сессии собираются из ответа
``/api/bot`` (:mod:`kofauth_common.cards`). Одно сообщение перерисовывается
и дальше, только теперь у него меняется не одна подпись, а подпись с картинкой.

**Картинка нигде не единственный носитель смысла.** Подтверждение входа,
предупреждения безопасности и ошибки остаются текстом с кнопками — их читают
в спешке, пересылают и цитируют, и ни одно из этих действий не работает с
изображением. У разделов картинка сопровождается подписью, а если нарисовать
или отправить её не вышло — тем же экраном обычным текстом. Оформление ни в
одном месте не является условием работы.
"""

from __future__ import annotations

import html
import logging
import os
from collections.abc import Awaitable, Callable
from typing import Any

from aiogram import Bot, Dispatcher, F
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramBadRequest
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.types import (
    BufferedInputFile,
    CallbackQuery,
    FSInputFile,
    InputMediaPhoto,
    Message,
)
from kofauth_common import (
    ApiUnavailable,
    BotMessage,
    Card,
    CardService,
    KoFAuthApi,
    OutboxListener,
    Settings,
    cards,
    strip_custom_emoji,
    texts,
)
from kofauth_common import events as event_kinds

from . import menu

LOGGER = logging.getLogger(__name__)

PLATFORM = "TELEGRAM"

#: Как площадка называется для человека. Показывается в подвале карточки
#: профиля: она и есть та привязка, о которой можно утверждать наверняка.
PLATFORM_NAME = "Telegram"

#: Отпечатки картинок, у которых нет содержимого: они не рисуются, а лежат
#: готовыми. Нужны затем же, зачем и настоящие, — чтобы помнить выданный
#: Telegram ``file_id`` и не грузить один и тот же файл на каждое открытие.
BANNER_MENU = "screen:menu"
BANNER_FILE = "screen:file"
BANNER_URL = "screen:url"

#: Префикс полезной нагрузки ссылки t.me/бот?start=…
#:
#: Ссылку показывает игра, и по ней бот получает код первым же сообщением —
#: игроку не приходится ничего переписывать. Префикс нужен, чтобы не путать
#: код привязки с другими возможными нагрузками start.
LINK_PAYLOAD_PREFIX = "link_"

#: Предел подписи к фотографии в Telegram. Текст длиннее просто не отправится,
#: поэтому длинные экраны обрезаются, а не теряются целиком.
CAPTION_LIMIT = 1024

#: Исходы, после которых запрос подтверждения действительно закрыт. Только они
#: позволяют убрать кнопки: всё остальное означает, что сервер решения не принял,
#: и отнимать у человека возможность нажать снова нельзя.
DECIDED = frozenset({"APPLIED", "ALREADY_DECIDED", "EXPIRED", "NOT_FOUND"})


def esc(value: Any) -> str:
    """Экранирует HTML: ник приходит извне, а Telegram разбирает разметку."""
    return html.escape(str(value), quote=False)


def block(title: str, lines: list[str], mark: str = "") -> str:
    """Заголовок экрана и его содержимое.

    :param mark: готовая разметка значка. Приходит из ``EmojiSet`` и потому
        не экранируется: там либо один символ, либо тег ``tg-emoji`` с
        числовым идентификатором. Заголовок и строки — данные, они
        экранируются всегда.
    """
    body = "\n".join(esc(line) for line in lines)
    head = f"<b>{esc(title)}</b>"
    return f"{f'{mark} ' if mark else ''}{head}\n\n{body}"


def _clip(text: str) -> str:
    """Укорачивает текст до предела подписи к фотографии.

    Подпись длиннее 1024 символов Telegram не принимает вовсе — сообщение не
    отправится. Обрезать по границе строки обязательно: разрыв посреди тега
    ``<b>`` сделал бы разметку незакрытой, и Telegram отверг бы уже её.
    """
    if len(text) <= CAPTION_LIMIT:
        return text
    kept: list[str] = []
    length = 0
    for line in text.split("\n"):
        if length + len(line) + 1 > CAPTION_LIMIT - 2:
            break
        kept.append(line)
        length += len(line) + 1
    return "\n".join(kept) + "\n…"


class Screen:
    """Один экран: картинка, подпись под ней и тот же экран без картинки.

    Две подачи вместо одной — не запас на всякий случай. Картинка может не
    нарисоваться, не отправиться или не подойти к сообщению, которое уже
    показано текстом; во всех этих случаях человек обязан увидеть тот же
    экран, а не пустоту. Поэтому у каждого экрана есть полный текст, и он
    строится всегда, даже когда картинка удалась.

    :param name: короткое имя для файла вложения и для журнала
    :param digest: отпечаток содержимого. По нему запоминается выданный
        Telegram ``file_id``, поэтому один и тот же экран грузится однажды.
        Пусто — картинки нет
    :param image: байты нарисованной карточки, путь к файлу или ссылка
    """

    __slots__ = ("name", "digest", "image", "caption", "text", "markup")

    def __init__(self, caption: str, text: str, markup: Any = None, *,
                 name: str = "screen", digest: str = "",
                 image: bytes | str | None = None) -> None:
        self.name = name
        self.digest = digest
        self.image = image
        self.caption = caption
        self.text = text
        self.markup = markup

    @classmethod
    def plain(cls, text: str, markup: Any = None) -> Screen:
        """Экран без картинки: подтверждения, ошибки, короткие ответы."""
        return cls(text, text, markup)


#: Значок в заголовке текстового экрана — по виду карточки. Берётся из общего
#: набора, чтобы кнопка «Профиль» и заголовок «Профиль» были помечены одним и
#: тем же значком.
SCREEN_MARKS = {
    cards.PROFILE: "profile",
    cards.SECURITY: "security",
    cards.HISTORY: "history",
    cards.SESSIONS: "sessions",
    cards.HELP: "help",
}

#: Заголовки текстовых экранов. У карточки заголовок свой, короткий — он
#: набран крупно и стоит по центру; в тексте место есть, и уточнение уместно.
SCREEN_TITLES = {
    cards.PROFILE: "Профиль",
    cards.SECURITY: "Защита аккаунта",
    cards.HISTORY: "Последние входы",
    cards.SESSIONS: "Активные сессии",
}


class TelegramBot:
    """Сборка бота: обработчики, меню, уведомления."""

    def __init__(self, settings: Settings, api: KoFAuthApi) -> None:
        self._settings = settings
        self._api = api
        self._bot = Bot(token=settings.telegram.token)
        self._dispatcher = Dispatcher()
        #: Набор значков. Не константа: право слать фирменные emoji есть не у
        #: каждого бота, и узнаётся это только по отказу Telegram — тогда набор
        #: заменяется на обычный, см. ``_without_custom_emoji``.
        self._emoji = settings.brand.emoji()
        #: Картинки экранов. Служба сама решает, рисовать ли, брать ли готовое
        #: и когда сдаться: отказ означает текстовый экран, а не отсутствие
        #: ответа. Выданные Telegram ``file_id`` она же и помнит — иначе каждое
        #: открытие меню означало бы выгрузку мегабайта на серверы Telegram.
        self._cards = CardService(settings.cards)
        self._register()

    @property
    def bot(self) -> Bot:
        return self._bot

    async def run(self) -> None:
        await self._dispatcher.start_polling(self._bot, handle_signals=False)

    async def close(self) -> None:
        self._cards.close()
        await self._bot.session.close()

    # ------------------------------------------------------------------ регистрация

    def _register(self) -> None:
        router = self._dispatcher

        router.message.register(self._on_start, CommandStart())
        router.message.register(self._on_link, Command("link"))
        router.message.register(self._on_menu, Command("menu"))
        router.message.register(self._on_profile, Command("profile"))
        router.message.register(self._on_history, Command("history"))
        router.message.register(self._on_security, Command("security"))
        router.message.register(self._on_unlink, Command("unlink"))
        router.message.register(self._on_help, Command("help"))
        router.message.register(self._on_login_hint, Command("login"))

        router.callback_query.register(self._on_menu_click, F.data.startswith("menu:"))
        router.callback_query.register(self._on_action, F.data.startswith("act:"))
        router.callback_query.register(self._on_approval, F.data.regexp(r"^(ok|no):"))

    # ------------------------------------------------------------------ команды

    async def _on_start(self, message: Message, command: CommandObject) -> None:
        """Начало разговора — и, возможно, сразу привязка.

        Игра показывает ссылку ``t.me/бот?start=link_КОД``. Telegram передаёт
        нагрузку первым же сообщением, поэтому переход по ссылке и есть
        привязка: игроку не приходится переписывать восьмизначный код руками —
        ровно тот барьер, из-за которого привязку не доводили до конца.

        Код всё так же выдаётся только в игре. Ссылка меняет способ доставки
        кода, а не направление привязки: получить код, не имея доступа к
        игровому аккаунту, по-прежнему нельзя.
        """
        payload = (command.args or "").strip()
        if payload.startswith(LINK_PAYLOAD_PREFIX):
            code = payload[len(LINK_PAYLOAD_PREFIX):].strip()
            if code:
                await self._complete_link(message, code)
                return
        await self._show_home(message)

    async def _on_menu(self, message: Message) -> None:
        await self._show_home(message)

    async def _show_home(self, message: Message) -> None:
        await self._deliver(message, await self._home_screen(message.from_user.id))

    def _main_menu(self, linked: bool):
        return menu.main_menu(linked, donate_url=self._settings.brand.donate_url,
                              emoji=self._emoji)

    def _mark(self, key: str) -> str:
        """Значок для текста сообщения — фирменный, если он настроен.

        Именно в тексте, а не на кнопке: надпись кнопки в Bot API сущностей не
        принимает, и custom emoji там показать нечем.
        """
        return self._emoji.telegram_html(key)

    def _welcome_text(self) -> str:
        lines = [
            f"{self._mark('brand')} <b>{esc(texts.BRAND)}</b>",
            "",
            "Я подтверждаю вход в игру и присылаю оповещения безопасности.",
            "",
            f"{self._mark('link')} <b>Как привязать аккаунт</b>",
            "1. Зайдите в игру",
            "2. Наберите <code>/telegram</code>",
            "3. Нажмите на ссылку в чате — привяжу сам",
            "",
            "Если ссылка не открылась, пришлите мне <code>/link КОД</code>.",
        ]
        return "\n".join(lines)

    def _home_text(self, profile: dict[str, Any]) -> str:
        return (
            f"{self._mark('brand')} <b>{esc(texts.BRAND)}</b>\n\n"
            f"{self._mark('profile')} Аккаунт: <b>{esc(profile.get('username', '—'))}</b>\n"
            f"{self._mark('history')} Последний вход: "
            f"{esc(texts.format_time(profile.get('lastLoginAt')))}\n\n"
            f"Выберите раздел."
        )

    async def _complete_link(self, message: Message, code: str) -> None:
        """Привязывает аккаунт по коду и сразу показывает главный экран."""
        result = await self._call(
            message, self._api.link(PLATFORM, code, message.from_user.id, message.chat.id)
        )
        if result is None:
            return
        if not result.ok:
            await self._deliver(message, await self._banner_screen(
                f"{self._mark('deny')} {esc(texts.describe_error(result.error))}",
                self._main_menu(linked=False),
            ))
            return

        await self._deliver(message, await self._banner_screen(
            f"{self._mark('ok')} Аккаунт "
            f"<b>{esc(result.data.get('username', ''))}</b> привязан.\n\n"
            "Теперь подтверждение входа приходит сюда кнопками.",
            self._main_menu(linked=True),
        ))

    # ------------------------------------------------------------------ экраны

    async def _home_screen(self, user_id: int) -> Screen:
        """Главный экран: готовый баннер сети и короткая подпись под ним."""
        profile = await self._profile_or_none(user_id)
        text = self._home_text(profile) if profile else self._welcome_text()
        return await self._banner_screen(text, self._main_menu(profile is not None))

    async def _banner_screen(self, text: str, markup) -> Screen:
        """Экран с баннером главного меню.

        Им показывается всё, у чего нет своей карточки: приглашение,
        подтверждение отвязки, ответ о привязке. Баннер здесь — фон сообщения,
        а не носитель смысла, поэтому подпись и запасной текст совпадают.
        """
        digest, image = self._banner()
        return Screen(text, text, markup, name="menu", digest=digest, image=image)

    def _banner(self) -> tuple[str, bytes | str | None]:
        """Что отправлять баннером и под каким отпечатком это помнить.

        Порядок источников: готовая картинка из ресурсов, затем локальный файл
        и ссылка из настроек. Последние два остались ради развёртываний, где
        баннер свой; ресурсы пакета их не отменяют, а лишь избавляют от
        необходимости что-то настраивать.
        """
        data = self._cards.menu()
        if data is not None:
            return BANNER_MENU, data
        path = self._settings.brand.banner_file
        if path and os.path.isfile(path):
            return BANNER_FILE, path
        url = self._settings.brand.banner_url
        return (BANNER_URL, url) if url else ("", None)

    async def _card_screen(self, card: Card, markup) -> Screen:
        """Экран-карточка: нарисованная картинка и тот же экран текстом."""
        image = await self._cards.image(card)
        mark = self._mark(SCREEN_MARKS.get(card.kind, "profile"))
        title = SCREEN_TITLES.get(card.kind, card.title)
        caption = f"{mark} <b>{esc(title)}</b>"
        if card.caption:
            caption += "\n\n" + "\n".join(esc(line) for line in card.caption)
        return Screen(caption, block(title, list(card.text), mark), markup,
                      name=card.kind, digest=card.digest() if image else "",
                      image=image)

    async def _on_link(self, message: Message) -> None:
        code = self._argument(message)
        if not code:
            await message.answer(
                "Укажите код: <code>/link КОД</code>\n\n"
                "Код берётся в игре командой <code>/telegram</code> — "
                "там же есть ссылка, по которой привязка происходит сама.",
                parse_mode=ParseMode.HTML,
            )
            return
        await self._complete_link(message, code)

    async def _on_profile(self, message: Message) -> None:
        profile = await self._require_profile(message)
        if profile is not None:
            await self._deliver(message, await self._card_screen(
                self._profile_card(profile), menu.back_only(self._emoji)))

    def _profile_card(self, profile: dict[str, Any]) -> Card:
        return cards.profile_card(
            profile, donate_url=self._settings.brand.donate_url,
            platform=PLATFORM_NAME,
        )

    async def _on_history(self, message: Message) -> None:
        result = await self._call(
            message, self._api.history(PLATFORM, message.from_user.id,
                                       limit=texts.HISTORY_LIMIT))
        if result is None:
            return
        if not result.ok:
            await message.answer(texts.NOT_LINKED.format(command="/telegram"))
            return
        card = cards.history_card(result.data.get("history", []))
        await self._deliver(message, await self._card_screen(
            card, menu.back_only(self._emoji)))

    async def _on_security(self, message: Message) -> None:
        profile = await self._require_profile(message)
        if profile is not None:
            await self._deliver(message, await self._card_screen(
                cards.security_card(profile),
                menu.security_menu(bool(profile.get("loginApproval")), self._emoji)))

    async def _on_unlink(self, message: Message) -> None:
        # Отвязка снижает защиту аккаунта, и спрашивают о ней текстом:
        # предупреждение, набранное внутри картинки, нельзя ни переслать
        # словами, ни прочитать голосом.
        await self._answer(
            message,
            "Отвязать Telegram? Подтверждение входа и уведомления перестанут работать.",
            menu.confirm_unlink(self._emoji),
        )

    async def _on_help(self, message: Message) -> None:
        await self._deliver(message, await self._help_screen())

    async def _help_screen(self) -> Screen:
        """Справка: запечённая картинка и ссылки под ней.

        Ссылки остаются в подписи, а не уезжают в картинку: адрес кабинета
        задаётся развёртыванием, в тексте он нажимается, а из изображения его
        пришлось бы переписывать руками.
        """
        card = cards.help_card(PLATFORM)
        image = await self._cards.help(card)
        caption = [f"{self._mark('help')} <b>Справка</b>", "",
                   f"{self._mark('site')} Личный кабинет: {esc(self._settings.panel_url)}"]
        if self._settings.brand.donate_url:
            caption.append(f"{self._mark('donate')} Поддержать сервер: "
                           f"{esc(self._settings.brand.donate_url)}")
        return Screen("\n".join(caption), self._help_text(),
                      menu.back_only(self._emoji), name=card.kind,
                      digest=card.digest() if image else "", image=image)

    def _help_text(self) -> str:
        lines = [
            f"{self._mark('help')} <b>Справка</b>",
            "",
            f"{self._mark('link')} <b>Как привязать аккаунт</b>",
            "1. Зайдите в игру",
            "2. Наберите <code>/telegram</code>",
            "3. Нажмите на ссылку в чате — я привяжу аккаунт сам",
            "",
            "Не открылась ссылка — пришлите <code>/link КОД</code>.",
            "Код выдаётся только в игре: это единственный способ доказать, "
            "что аккаунт ваш.",
            "",
            f"{self._mark('commands')} <b>Команды</b>",
            "/menu — главное меню",
            "/profile — сведения об аккаунте",
            "/security — защита и подтверждение входа",
            "/history — история входов",
            "/unlink — отвязать Telegram",
            "",
            f"{self._mark('site')} Личный кабинет: {esc(self._settings.panel_url)}",
        ]
        if self._settings.brand.donate_url:
            lines.append(
                f"{self._mark('donate')} Поддержать сервер: "
                f"{esc(self._settings.brand.donate_url)}"
            )
        return "\n".join(lines)

    async def _on_login_hint(self, message: Message) -> None:
        await message.answer(
            "Подтверждение входа приходит сюда само, когда вы заходите в игру: "
            "две кнопки, «Войти» и «Отклонить».\n\n"
            "Вводить в игре ничего не нужно. Если сообщение не пришло — "
            "повторите вход в Minecraft, запрос придёт заново."
        )

    # ------------------------------------------------------------------ меню

    async def _on_menu_click(self, query: CallbackQuery) -> None:
        """Переход по меню: то же сообщение, другая картинка и другие кнопки.

        Данные кнопок не изменились ни на байт — навигация та же, что была до
        картинок. Поменялось только то, чем экран нарисован.
        """
        screen = query.data.split(":", 1)[1]
        user_id = query.from_user.id

        if screen == menu.HELP:
            await self._redraw(query, await self._help_screen())
            await query.answer()
            return

        if screen == menu.HOME:
            await self._redraw(query, await self._home_screen(user_id))
            await query.answer()
            return

        profile = await self._profile_or_none(user_id)
        if profile is None:
            await self._redraw(query, await self._banner_screen(
                self._welcome_text(), self._main_menu(linked=False)))
            await query.answer()
            return

        if screen == menu.PROFILE:
            await self._redraw(query, await self._card_screen(
                self._profile_card(profile), menu.back_only(self._emoji)))
        elif screen == menu.SECURITY:
            await self._redraw(query, await self._card_screen(
                cards.security_card(profile),
                menu.security_menu(bool(profile.get("loginApproval")), self._emoji)))
        elif screen == menu.HISTORY:
            result = await self._fetch(query, self._api.history(
                PLATFORM, user_id, limit=texts.HISTORY_LIMIT))
            if result is None:
                return
            await self._redraw(query, await self._card_screen(
                cards.history_card(result.data.get("history", [])),
                menu.back_only(self._emoji)))
        elif screen == menu.SESSIONS:
            result = await self._fetch(query, self._api.sessions(PLATFORM, user_id))
            if result is None:
                return
            await self._redraw(query, await self._card_screen(
                cards.sessions_card(result.data.get("sessions", [])),
                menu.back_only(self._emoji)))
        await query.answer()

    async def _fetch(self, query: CallbackQuery, awaitable):
        """Запрос из обработчика кнопки.

        Недоступный API — не повод перерисовывать экран: человек и так стоит
        на рабочем, и всплывающее окно сообщает ему больше, чем подменённый
        неизвестно чем раздел.
        """
        try:
            return await awaitable
        except ApiUnavailable:
            await query.answer(texts.API_UNAVAILABLE, show_alert=True)
            return None

    async def _on_action(self, query: CallbackQuery) -> None:
        action = query.data.split(":", 1)[1]
        user_id = query.from_user.id

        if action in {"approval:on", "approval:off"}:
            enabled = action.endswith(":on")
            result = await self._fetch(
                query, self._api.set_login_approval(PLATFORM, user_id, enabled))
            if result is None:
                return
            profile = result.data if result.ok else await self._api_profile(user_id)
            await self._redraw(query, await self._card_screen(
                cards.security_card(profile),
                menu.security_menu(bool(profile.get("loginApproval")), self._emoji)))
            await query.answer("Готово" if result.ok else "Не получилось")
            return

        if action == "logout":
            result = await self._fetch(query, self._api.logout_all(PLATFORM, user_id))
            if result is None:
                return
            await query.answer(
                f"Завершено сессий: {result.data.get('revoked', 0)}" if result.ok
                else "Не получилось", show_alert=True,
            )
            return

        if action == "unlink:ask":
            # Вопрос об отвязке остаётся текстом поверх баннера: он о потере
            # защиты, и прочитать его человек должен словами, а не рассмотреть.
            await self._redraw(query, await self._banner_screen(
                f"{self._mark('warning')} <b>Отвязать Telegram?</b>\n\n"
                "Подтверждение входа и уведомления перестанут работать.",
                menu.confirm_unlink(self._emoji),
            ))
            await query.answer()
            return

        if action == "unlink:yes":
            result = await self._fetch(query, self._api.unlink(PLATFORM, user_id))
            if result is None:
                return
            await self._redraw(query, await self._banner_screen(
                f"{self._mark('unlink')} Аккаунт отвязан." if result.ok
                else f"{self._mark('deny')} " + esc(texts.describe_error(result.error)),
                self._main_menu(linked=not result.ok),
            ))
            await query.answer()

    # ------------------------------------------------------------------ подтверждение входа

    async def _on_approval(self, query: CallbackQuery) -> None:
        """Нажатие кнопки подтверждения.

        Решение принимает сервер. Бот не проверяет ни срок, ни владельца сам:
        обе проверки обязаны выполняться там же, где записывается результат,
        иначе между проверкой и записью помещается второе нажатие.

        Идентификатор нажавшего берётся из данных Telegram, а не из сообщения:
        подставить чужой человек не может, а сервер по нему и сверяет, тому ли
        адресована кнопка.
        """
        approve = query.data.startswith("ok:")
        approval_id = query.data.split(":", 1)[1]

        try:
            result = await self._api.approve(
                PLATFORM, query.from_user.id, approval_id, approve
            )
        except ApiUnavailable:
            # Сообщение не правим и кнопки не убираем: запрос, возможно, ещё жив,
            # и человек сможет нажать снова.
            await query.answer(texts.API_UNAVAILABLE, show_alert=True)
            return

        outcome = str(result.data.get("result") or "")
        text = self._approval_outcome_text(outcome, approve)

        if outcome == "FOREIGN":
            # Кнопка адресована не этому человеку. Исходное сообщение чужое,
            # править его нельзя — отвечаем всплывающим окном.
            await query.answer(text, show_alert=True)
            return

        if outcome not in DECIDED:
            # Сервер решения не записал: неверный ключ бота, отвергнутый запрос,
            # незнакомый ответ. Сообщение не правим и кнопки не убираем — запрос,
            # возможно, ещё жив, и это единственный способ довести вход до конца.
            await query.answer(text, show_alert=True)
            return

        # Кнопки убираем: решение уже принято, и нажимать больше нечего.
        # Исход остаётся текстом — им отвечают, его пересылают, по нему ищут.
        await self._redraw(query, Screen.plain(text))
        await query.answer()

    @staticmethod
    def _approval_outcome_text(outcome: str, approve: bool) -> str:
        if outcome == "APPLIED":
            return (
                f"{texts.ICON['ok']} Вход подтверждён." if approve
                else f"{texts.ICON['deny']} Вход отклонён. "
                     "Если это были не вы — смените пароль."
            )
        if outcome == "ALREADY_DECIDED":
            return "Этот запрос уже обработан."
        if outcome == "EXPIRED":
            return "⌛ Время подтверждения истекло. Зайдите в игру ещё раз."
        if outcome == "FOREIGN":
            return "Эта кнопка адресована другому человеку."
        if outcome == "NOT_FOUND":
            return "Запрос не найден: возможно, он уже устарел."
        return "Не получилось обработать нажатие."

    async def request_approval(self, message: BotMessage) -> None:
        """Присылает запрос подтверждения входа с двумя кнопками."""
        lines = texts.approval_request_lines(
            message.get("username", "—"),
            message.get("ip", "—"),
            texts.format_location(message.get("country"), message.get("city")),
        )
        await self._safe_send(
            message.chat_id or message.recipient_id,
            f"{self._mark('lock')} <b>" + esc(lines[0]) + "</b>\n\n"
            + "\n".join(esc(x) for x in lines[1:]),
            menu.approval_keyboard(message.get("approvalId"), self._emoji),
        )

    async def notify(self, chat_id: int, text: str) -> None:
        await self._safe_send(chat_id, text, None)

    # ------------------------------------------------------------------ вспомогательное

    @staticmethod
    def _argument(message: Message) -> str:
        parts = (message.text or "").split(maxsplit=1)
        return parts[1].strip() if len(parts) > 1 else ""

    async def _profile_or_none(self, user_id: int) -> dict[str, Any] | None:
        try:
            result = await self._api.account(PLATFORM, user_id)
        except ApiUnavailable:
            return None
        return result.data if result.ok else None

    async def _api_profile(self, user_id: int) -> dict[str, Any]:
        return await self._profile_or_none(user_id) or {}

    async def _require_profile(self, message: Message) -> dict[str, Any] | None:
        """Профиль для экрана, который без него не имеет смысла.

        Недоступность API и отсутствие привязки разделены. Прежде оба случая
        отвечали «аккаунт не привязан»: во время перезапуска WebAPI бот предлагал
        привязанному человеку взять код в игре — совет, который не сработал бы,
        потому что за кодом идёт запрос к тому же API.
        """
        result = await self._call(message, self._api.account(PLATFORM,
                                                             message.from_user.id))
        if result is None:
            return None
        if not result.ok:
            await message.answer(texts.NOT_LINKED.format(command="/telegram"))
            return None
        return result.data

    async def _call(self, message: Message, awaitable):
        """Выполняет запрос, отвечая на недоступность API понятным текстом."""
        try:
            return await awaitable
        except ApiUnavailable:
            await message.answer(texts.API_UNAVAILABLE)
            return None

    # -------------------------------------------------------------- доставка

    def _media(self, screen: Screen):
        """Что положить в запрос как картинку.

        Сначала — идентификатор, выданный Telegram в прошлый раз: он весит
        строку и не требует выгрузки. Его нет — отдаём байты или файл, а
        идентификатор запомним из ответа.
        """
        if not screen.digest or screen.image is None:
            return None
        handle = self._cards.handle(screen.digest)
        if handle:
            return handle
        if isinstance(screen.image, bytes):
            return BufferedInputFile(screen.image, filename=f"{screen.name}.jpg")
        if os.path.isfile(screen.image):
            return FSInputFile(screen.image)
        return screen.image

    def _remember(self, screen: Screen, sent: Any) -> None:
        """Запоминает идентификатор картинки, если Telegram его выдал."""
        photo = getattr(sent, "photo", None)
        if screen.digest and photo:
            self._cards.remember(screen.digest, photo[-1].file_id)

    async def _deliver(self, message: Message, screen: Screen) -> None:
        """Отправляет экран новым сообщением.

        Картинка идёт с подписью, а не отдельным сообщением: так экран
        остаётся одним сообщением, которое кнопки перерисовывают, и в чате не
        копится лента из картинки и текста к ней.
        """
        media = self._media(screen)
        if media is None:
            await self._answer(message, screen.text, screen.markup)
            return
        try:
            sent = await self._with_fallback(
                lambda body: message.answer_photo(
                    media, caption=_clip(body), parse_mode=ParseMode.HTML,
                    reply_markup=screen.markup,
                ),
                screen.caption,
            )
        except Exception as exc:  # noqa: BLE001 — Telegram мог отвергнуть картинку
            # Картинка не ушла — экран всё равно должен дойти. Полным текстом:
            # подпись рассчитана на то, что данные видны на изображении.
            LOGGER.warning("Экран %s отправлен без картинки: %s", screen.name, exc)
            await self._answer(message, screen.text, screen.markup)
            return
        self._remember(screen, sent)

    async def _redraw(self, query: CallbackQuery, screen: Screen) -> None:
        """Перерисовывает то же сообщение под новый экран.

        Три случая, и все три встречаются в жизни:

        * у сообщения есть картинка и у экрана есть — меняется и картинка, и
          подпись одним запросом;
        * у сообщения картинка есть, а у экрана нет (не нарисовалась) —
          правится подпись, и в неё идёт полный текст: прежняя картинка этот
          экран уже не описывает;
        * картинки нет вовсе — обычная правка текста.

        Сообщение с фотографией нельзя превратить в текстовое и наоборот:
        Telegram таких правок не принимает. Поэтому выбор подачи делается по
        тому, чем сообщение уже является, а не по тому, чем мы хотели бы его
        видеть.
        """
        message = query.message
        if message is None:  # pragma: no cover — сообщение старше двух суток
            return
        photo = bool(message.photo)
        media = self._media(screen) if photo else None
        body = screen.caption if media is not None else screen.text

        async def send(text: str) -> Any:
            if media is not None:
                return await message.edit_media(
                    InputMediaPhoto(media=media, caption=_clip(text),
                                    parse_mode=ParseMode.HTML),
                    reply_markup=screen.markup,
                )
            if photo:
                return await message.edit_caption(
                    caption=_clip(text), parse_mode=ParseMode.HTML,
                    reply_markup=screen.markup,
                )
            return await message.edit_text(text, parse_mode=ParseMode.HTML,
                                           reply_markup=screen.markup)

        try:
            self._remember(screen, await self._with_fallback(send, body))
        except TelegramBadRequest as exc:
            # «message is not modified» — обычное дело при повторном нажатии
            # той же кнопки и не ошибка для человека.
            if "not modified" not in str(exc):
                LOGGER.warning("Не удалось обновить сообщение: %s", exc)
                await self._fallback_caption(message, screen, photo)
        except Exception as exc:  # noqa: BLE001 — картинку могли не принять
            LOGGER.warning("Экран %s не перерисован: %s", screen.name, exc)
            await self._fallback_caption(message, screen, photo)

    async def _fallback_caption(self, message: Message, screen: Screen,
                                photo: bool) -> None:
        """Последняя попытка: тот же экран текстом, без картинки.

        Если не выходит и она, дальше делать нечего — человек уже видит
        предыдущий экран с рабочими кнопками, и это лучше, чем сообщение об
        ошибке вместо него.
        """
        try:
            if photo:
                await message.edit_caption(caption=_clip(screen.text),
                                           parse_mode=ParseMode.HTML,
                                           reply_markup=screen.markup)
            else:
                await message.edit_text(screen.text, parse_mode=ParseMode.HTML,
                                        reply_markup=screen.markup)
        except TelegramBadRequest as exc:
            if "not modified" not in str(exc):
                LOGGER.warning("Экран %s не показан вовсе: %s", screen.name, exc)

    async def _answer(self, message: Message, text: str, markup=None) -> None:
        await self._with_fallback(
            lambda body: message.answer(body, parse_mode=ParseMode.HTML,
                                        reply_markup=markup),
            text,
        )

    async def _with_fallback(self, send: Callable[[str], Awaitable[Any]], text: str) -> Any:
        """Отправляет текст, а на отказ от фирменных emoji — тот же текст без них.

        Право слать custom emoji есть не у каждого бота, и узнать это заранее
        нельзя: Telegram сообщает об отказе только в ответ на отправку. Вместо
        того чтобы потерять сообщение целиком, значки снимаются до запасных
        символов, и человек получает обычный экран.
        """
        try:
            return await send(text)
        except TelegramBadRequest as exc:
            plain = self._without_custom_emoji(text, exc)
            if plain is None:
                raise
            return await send(plain)

    def _without_custom_emoji(self, text: str, exc: TelegramBadRequest) -> str | None:
        """Текст без фирменных emoji — или ``None``, если отказ не про них.

        Заодно переключает бота на обычные значки: один и тот же отказ на
        каждом экране означал бы двойную отправку каждого сообщения.
        """
        if "custom emoji" not in str(exc).lower():
            return None
        plain = strip_custom_emoji(text)
        if plain == text:
            return None
        if self._emoji.has_telegram_custom:
            self._emoji = self._emoji.without_telegram_custom()
            LOGGER.warning(
                "Telegram не принял фирменные emoji, дальше показываю обычные значки"
            )
        return plain

    async def _safe_send(self, chat_id: int, text: str, markup) -> None:
        try:
            await self._with_fallback(
                lambda body: self._bot.send_message(chat_id, body,
                                                    parse_mode=ParseMode.HTML,
                                                    reply_markup=markup),
                text,
            )
        except Exception as exc:  # noqa: BLE001 — бота могли заблокировать
            LOGGER.warning("Сообщение в чат %s не доставлено: %s", chat_id, exc)


def register_message_handlers(bot: TelegramBot, listener: OutboxListener) -> None:
    """Подписывает бота на очередь сообщений.

    Получатель берётся из сообщения: связка «аккаунт → чат» живёт в базе
    KoFAuth, и дублировать её в памяти бота значило бы терять её при
    перезапуске.

    Доставка «не меньше одного раза»: одно и то же сообщение может прийти
    дважды, если бот упал между обработкой и подтверждением. Для запроса
    подтверждения это безопасно — повторно показанная кнопка ведёт к тому же
    самому запросу, а решение по нему всё равно принимается один раз.
    """

    async def on_approval(message: BotMessage) -> None:
        if not message.get("approvalId"):
            return
        await bot.request_approval(message)

    async def on_resolved(message: BotMessage) -> None:
        # Запрос закрыт где-то ещё — например, игрок повторил вход, и прежняя
        # кнопка обесценилась. Отдельного сообщения не шлём: нажатие такой
        # кнопки и так ответит «запрос устарел».
        LOGGER.debug("Запрос %s закрыт со статусом %s",
                     message.get("approvalId"), message.get("status"))

    async def on_notice(message: BotMessage) -> None:
        template = NOTICES.get(message.get("event"))
        if not template:
            return
        await bot.notify(message.chat_id or message.recipient_id, template.format(
            username=esc(message.get("username", "")),
            ip=esc(message.get("ip", "—")),
            at=esc(texts.format_time(message.get("at"))),
        ))

    listener.on(event_kinds.LOGIN_APPROVAL, on_approval)
    listener.on(event_kinds.LOGIN_APPROVAL_RESOLVED, on_resolved)
    listener.on(event_kinds.LOGIN_NOTICE, on_notice)
    listener.on(event_kinds.SECURITY_NOTICE, on_notice)


#: Значки берутся из общего набора, а не пишутся здесь символами: иначе
#: уведомление и экран, о котором оно говорит, помечены разными картинками.
NOTICES = {
    "AccountLoginEvent": f"{texts.ICON['approve']} Вход в аккаунт {{username}}\n"
                         "Адрес: {ip}\n{at}",
    "PasswordChangedEvent": f"{texts.ICON['lock']} Пароль изменён.\n{{at}}\n\n"
                            "Если это были не вы — срочно обратитесь к администрации.",
    "SuspiciousActivityEvent": f"{texts.ICON['warning']} Подозрительная активность "
                               "в аккаунте {username}.\nАдрес: {ip}\n{at}",
    "SessionInvalidatedEvent": f"{texts.ICON['logout']} Сессии аккаунта завершены.\n{{at}}",
    "BindingChangedEvent": f"{texts.ICON['link']} Привязки аккаунта изменены.\n{{at}}",
}
