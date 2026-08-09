"""Telegram-бот KoF Network.

Направление привязки то же, что и везде в системе: код выдаётся в игре и
вводится здесь. Обратный порядок позволил бы привязать свой Telegram к чужому
нику — достаточно было бы знать ник.

Бот тонкий: он не знает ни паролей, ни устройства базы. Всё, что он делает,
проходит через ``/api/bot``, а уведомления приходят из общего канала событий.
"""

from __future__ import annotations

import html
import logging
from typing import Any

from aiogram import Bot, Dispatcher, F
from aiogram.enums import ParseMode
from aiogram.filters import Command, CommandStart
from aiogram.types import CallbackQuery, Message
from aiogram.exceptions import TelegramBadRequest

from kofauth_common import ApiUnavailable, Event, EventListener, KoFAuthApi, Settings
from kofauth_common import texts

from . import menu

LOGGER = logging.getLogger(__name__)

PLATFORM = "TELEGRAM"


def esc(value: Any) -> str:
    """Экранирует HTML: ник приходит извне, а Telegram разбирает разметку."""
    return html.escape(str(value), quote=False)


def block(title: str, lines: list[str]) -> str:
    body = "\n".join(esc(line) for line in lines)
    return f"<b>{esc(title)}</b>\n\n{body}"


class TelegramBot:
    """Сборка бота: обработчики, меню, уведомления."""

    def __init__(self, settings: Settings, api: KoFAuthApi) -> None:
        self._settings = settings
        self._api = api
        self._bot = Bot(token=settings.telegram.token)
        self._dispatcher = Dispatcher()
        self._register()

    @property
    def bot(self) -> Bot:
        return self._bot

    async def run(self) -> None:
        await self._dispatcher.start_polling(self._bot, handle_signals=False)

    async def close(self) -> None:
        await self._bot.session.close()

    # ------------------------------------------------------------------ регистрация

    def _register(self) -> None:
        router = self._dispatcher

        router.message.register(self._on_start, CommandStart())
        router.message.register(self._on_link, Command("link"))
        router.message.register(self._on_menu, Command("menu"))
        router.message.register(self._on_profile, Command("profile"))
        router.message.register(self._on_devices, Command("devices"))
        router.message.register(self._on_history, Command("history"))
        router.message.register(self._on_security, Command("security"))
        router.message.register(self._on_sendcode, Command("sendcode"))
        router.message.register(self._on_unlink, Command("unlink"))
        router.message.register(self._on_help, Command("help"))
        router.message.register(self._on_login_hint, Command("login"))

        router.callback_query.register(self._on_menu_click, F.data.startswith("menu:"))
        router.callback_query.register(self._on_action, F.data.startswith("act:"))
        router.callback_query.register(self._on_approval, F.data.regexp(r"^(ok|no):"))

    # ------------------------------------------------------------------ команды

    async def _on_start(self, message: Message) -> None:
        await self._show_home(message)

    async def _on_menu(self, message: Message) -> None:
        await self._show_home(message)

    async def _show_home(self, message: Message) -> None:
        profile = await self._profile_or_none(message.from_user.id)
        if profile is None:
            await message.answer(
                self._welcome_text(), parse_mode=ParseMode.HTML,
                reply_markup=menu.main_menu(linked=False),
            )
            return
        await message.answer(
            self._home_text(profile), parse_mode=ParseMode.HTML,
            reply_markup=menu.main_menu(linked=True),
        )

    def _welcome_text(self) -> str:
        return (
            "<b>KoF Network</b>\n\n"
            "Я подтверждаю вход в игру и присылаю оповещения безопасности.\n\n"
            "Чтобы начать, зайдите в игру, наберите <code>/telegram</code> "
            "и пришлите мне полученный код:\n"
            "<code>/link КОД</code>"
        )

    def _home_text(self, profile: dict[str, Any]) -> str:
        return (
            f"<b>KoF Network</b>\n\n"
            f"Аккаунт: <b>{esc(profile.get('username', '—'))}</b>\n"
            f"Последний вход: {esc(texts.format_time(profile.get('lastLoginAt')))}\n\n"
            f"Выберите раздел."
        )

    async def _on_link(self, message: Message) -> None:
        code = self._argument(message)
        if not code:
            await message.answer(
                "Укажите код: <code>/link КОД</code>\n\n"
                "Код берётся в игре командой <code>/telegram</code>.",
                parse_mode=ParseMode.HTML,
            )
            return

        result = await self._call(
            message, self._api.link(PLATFORM, code, message.from_user.id, message.chat.id)
        )
        if result is None:
            return
        if not result.ok:
            await message.answer("❌ " + texts.describe_error(result.error))
            return

        await message.answer(
            f"✅ Аккаунт <b>{esc(result.data.get('username', ''))}</b> привязан.",
            parse_mode=ParseMode.HTML,
            reply_markup=menu.main_menu(linked=True),
        )

    async def _on_profile(self, message: Message) -> None:
        profile = await self._require_profile(message)
        if profile is not None:
            await message.answer(
                block("Профиль", texts.profile_lines(profile)),
                parse_mode=ParseMode.HTML, reply_markup=menu.back_only(),
            )

    async def _on_devices(self, message: Message) -> None:
        await self._send_list(message, self._api.devices(PLATFORM, message.from_user.id),
                              "Устройства", "devices", texts.device_lines)

    async def _on_history(self, message: Message) -> None:
        await self._send_list(message, self._api.history(PLATFORM, message.from_user.id),
                              "Последние входы", "history", texts.history_lines)

    async def _on_security(self, message: Message) -> None:
        profile = await self._require_profile(message)
        if profile is not None:
            await message.answer(
                block("Защита аккаунта", texts.security_lines(profile)),
                parse_mode=ParseMode.HTML,
                reply_markup=menu.security_menu(bool(profile.get("loginApproval"))),
            )

    async def _on_sendcode(self, message: Message) -> None:
        result = await self._call(message, self._api.send_code(PLATFORM, message.from_user.id))
        if result is None:
            return
        if not result.ok:
            await message.answer("❌ " + texts.describe_error(result.error))
            return
        await message.answer(self._code_text(result.data), parse_mode=ParseMode.HTML)

    @staticmethod
    def _code_text(data: dict[str, Any]) -> str:
        code = esc(data.get("code", ""))
        seconds = int(data.get("expiresInSeconds", 120) or 120)
        return (
            f"🔑 Код подтверждения входа:\n\n<code>{code}</code>\n\n"
            f"Введите в игре: <code>/login пароль {code}</code>\n"
            f"Действует {seconds // 60} мин и срабатывает один раз."
        )

    async def _on_unlink(self, message: Message) -> None:
        await message.answer(
            "Отвязать Telegram? Подтверждение входа и уведомления перестанут работать.",
            reply_markup=menu.confirm_unlink(),
        )

    async def _on_help(self, message: Message) -> None:
        await message.answer(self._help_text(), parse_mode=ParseMode.HTML,
                             reply_markup=menu.back_only())

    def _help_text(self) -> str:
        return (
            "<b>Справка</b>\n\n"
            "<b>Как привязать аккаунт</b>\n"
            "1. Зайдите в игру\n"
            "2. Наберите <code>/telegram</code>\n"
            "3. Пришлите мне <code>/link КОД</code>\n\n"
            "Код выдаётся только в игре — это единственный способ доказать, "
            "что аккаунт ваш.\n\n"
            "<b>Команды</b>\n"
            "/menu — главное меню\n"
            "/profile — сведения об аккаунте\n"
            "/security — защита и подтверждение входа\n"
            "/devices — устройства\n"
            "/history — история входов\n"
            "/sendcode — код для ручного ввода в игре\n"
            "/unlink — отвязать Telegram\n\n"
            f"Личный кабинет: {esc(self._settings.panel_url)}"
        )

    async def _on_login_hint(self, message: Message) -> None:
        await message.answer(
            "Подтверждение входа приходит сюда само, когда вы заходите в игру.\n\n"
            "Если сообщение с кнопками не пришло — возьмите код командой /sendcode."
        )

    # ------------------------------------------------------------------ меню

    async def _on_menu_click(self, query: CallbackQuery) -> None:
        screen = query.data.split(":", 1)[1]
        user_id = query.from_user.id

        if screen == menu.HELP:
            await self._edit(query, self._help_text(), menu.back_only())
            return

        if screen == menu.HOME:
            profile = await self._profile_or_none(user_id)
            text = self._home_text(profile) if profile else self._welcome_text()
            await self._edit(query, text, menu.main_menu(linked=profile is not None))
            return

        profile = await self._profile_or_none(user_id)
        if profile is None:
            await self._edit(query, self._welcome_text(), menu.main_menu(linked=False))
            return

        if screen == menu.PROFILE:
            await self._edit(query, block("Профиль", texts.profile_lines(profile)),
                             menu.back_only())
        elif screen == menu.SECURITY:
            await self._edit(query, block("Защита аккаунта", texts.security_lines(profile)),
                             menu.security_menu(bool(profile.get("loginApproval"))))
        elif screen == menu.DEVICES:
            result = await self._api.devices(PLATFORM, user_id)
            await self._edit(query, block("Устройства",
                                          texts.device_lines(result.data.get("devices", []))),
                             menu.back_only())
        elif screen == menu.HISTORY:
            result = await self._api.history(PLATFORM, user_id)
            await self._edit(query, block("Последние входы",
                                          texts.history_lines(result.data.get("history", []))),
                             menu.back_only())
        elif screen == menu.SESSIONS:
            result = await self._api.sessions(PLATFORM, user_id)
            await self._edit(query, block("Активные сессии",
                                          texts.session_lines(result.data.get("sessions", []))),
                             menu.back_only())
        await query.answer()

    async def _on_action(self, query: CallbackQuery) -> None:
        action = query.data.split(":", 1)[1]
        user_id = query.from_user.id

        if action == "sendcode":
            result = await self._api.send_code(PLATFORM, user_id)
            # Код уходит отдельным сообщением, а не правкой экрана: его копируют,
            # и он не должен исчезнуть при следующем шаге по меню.
            await query.message.answer(
                self._code_text(result.data) if result.ok
                else "❌ " + texts.describe_error(result.error),
                parse_mode=ParseMode.HTML,
            )
            await query.answer()
            return

        if action in {"approval:on", "approval:off"}:
            enabled = action.endswith(":on")
            result = await self._api.set_login_approval(PLATFORM, user_id, enabled)
            profile = result.data if result.ok else await self._api_profile(user_id)
            await self._edit(query, block("Защита аккаунта", texts.security_lines(profile)),
                             menu.security_menu(bool(profile.get("loginApproval"))))
            await query.answer("Готово" if result.ok else "Не получилось")
            return

        if action == "logout":
            result = await self._api.logout_all(PLATFORM, user_id)
            await query.answer(
                f"Завершено сессий: {result.data.get('revoked', 0)}" if result.ok
                else "Не получилось", show_alert=True,
            )
            return

        if action == "unlink:ask":
            await self._edit(
                query,
                "Отвязать Telegram?\n\n"
                "Подтверждение входа и уведомления перестанут работать.",
                menu.confirm_unlink(),
            )
            await query.answer()
            return

        if action == "unlink:yes":
            result = await self._api.unlink(PLATFORM, user_id)
            await self._edit(
                query,
                "Аккаунт отвязан." if result.ok else "❌ " + texts.describe_error(result.error),
                menu.main_menu(linked=not result.ok),
            )
            await query.answer()

    # ------------------------------------------------------------------ подтверждение входа

    async def _on_approval(self, query: CallbackQuery) -> None:
        approve = query.data.startswith("ok:")
        token = query.data.split(":", 1)[1]

        try:
            result = await self._api.approve(token, approve)
        except ApiUnavailable:
            await query.answer(texts.API_UNAVAILABLE, show_alert=True)
            return

        if not approve:
            text = "❌ Вход отклонён. Если это были не вы — смените пароль."
        elif result.ok and result.data.get("ok"):
            text = "✅ Вход подтверждён."
        else:
            text = "⌛ Запрос устарел или уже обработан."

        # Правим исходное сообщение и убираем кнопки: повторно нажимать нечего,
        # токен уже погашен.
        await self._edit(query, text, None)
        await query.answer()

    async def request_approval(
        self, chat_id: int, token: str, username: str, ip: str, location: str
    ) -> None:
        """Присылает запрос подтверждения входа с двумя кнопками."""
        lines = texts.approval_request_lines(username, ip, location)
        await self._safe_send(
            chat_id,
            "🔐 <b>" + esc(lines[0]) + "</b>\n\n" + "\n".join(esc(x) for x in lines[1:]),
            menu.approval_keyboard(token),
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
        profile = await self._profile_or_none(message.from_user.id)
        if profile is None:
            await message.answer(texts.NOT_LINKED.format(command="/telegram"))
        return profile

    async def _send_list(self, message: Message, awaitable, title: str,
                         key: str, formatter) -> None:
        result = await self._call(message, awaitable)
        if result is None:
            return
        if not result.ok:
            await message.answer(texts.NOT_LINKED.format(command="/telegram"))
            return
        await message.answer(block(title, formatter(result.data.get(key, []))),
                             parse_mode=ParseMode.HTML, reply_markup=menu.back_only())

    async def _call(self, message: Message, awaitable):
        """Выполняет запрос, отвечая на недоступность API понятным текстом."""
        try:
            return await awaitable
        except ApiUnavailable:
            await message.answer(texts.API_UNAVAILABLE)
            return None

    async def _edit(self, query: CallbackQuery, text: str, markup) -> None:
        try:
            await query.message.edit_text(text, parse_mode=ParseMode.HTML,
                                          reply_markup=markup)
        except TelegramBadRequest as exc:
            # «message is not modified» — обычное дело при повторном нажатии
            # той же кнопки и не ошибка для человека.
            if "not modified" not in str(exc):
                LOGGER.warning("Не удалось обновить сообщение: %s", exc)

    async def _safe_send(self, chat_id: int, text: str, markup) -> None:
        try:
            await self._bot.send_message(chat_id, text, parse_mode=ParseMode.HTML,
                                         reply_markup=markup)
        except Exception as exc:  # noqa: BLE001 — бота могли заблокировать
            LOGGER.warning("Сообщение в чат %s не доставлено: %s", chat_id, exc)


def register_event_handlers(bot: TelegramBot, listener: EventListener) -> None:
    """Подписывает бота на события KoFAuth.

    Чат берётся из события: связка «аккаунт → чат» живёт в базе KoFAuth,
    и дублировать её в памяти бота значило бы терять её при перезапуске.
    """

    async def on_approval(event: Event) -> None:
        chat_id = event.get("telegramChatId")
        token = event.get("approvalToken")
        if not chat_id.isdigit() or not token:
            return
        await bot.request_approval(
            int(chat_id), token, event.get("username", "—"),
            event.get("ipMasked", "—"),
            texts.format_location(event.get("country"), event.get("city")),
        )

    async def on_notice(event: Event) -> None:
        chat_id = event.get("telegramChatId")
        if not chat_id.isdigit():
            return
        message = NOTICES.get(event.type)
        if message:
            await bot.notify(int(chat_id), message.format(
                username=esc(event.get("username", "")),
                ip=esc(event.get("ipMasked", "—")),
                at=esc(texts.format_time(event.get("at"))),
            ))

    listener.on("LoginApprovalRequestedEvent", on_approval)
    for event_type in NOTICES:
        listener.on(event_type, on_notice)


NOTICES = {
    "AccountLoginEvent": "🔓 Вход в аккаунт {username}\nАдрес: {ip}\n{at}",
    "PasswordChangedEvent": "🔑 Пароль изменён.\n{at}\n\nЕсли это были не вы — "
                            "срочно обратитесь к администрации.",
    "SuspiciousActivityEvent": "⚠️ Подозрительная активность в аккаунте {username}.\n"
                               "Адрес: {ip}\n{at}",
    "SessionInvalidatedEvent": "🚪 Сессии аккаунта завершены.\n{at}",
    "BindingChangedEvent": "🔗 Привязки аккаунта изменены.\n{at}",
}
