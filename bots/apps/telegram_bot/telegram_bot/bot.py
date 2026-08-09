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
"""

from __future__ import annotations

import html
import logging
from typing import Any

from aiogram import Bot, Dispatcher, F
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramBadRequest
from aiogram.filters import Command, CommandStart
from aiogram.types import CallbackQuery, Message
from kofauth_common import (
    ApiUnavailable,
    BotMessage,
    KoFAuthApi,
    OutboxListener,
    Settings,
    texts,
)
from kofauth_common import events as event_kinds

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
            "/unlink — отвязать Telegram\n\n"
            f"Личный кабинет: {esc(self._settings.panel_url)}"
        )

    async def _on_login_hint(self, message: Message) -> None:
        await message.answer(
            "Подтверждение входа приходит сюда само, когда вы заходите в игру: "
            "две кнопки, «Войти» и «Отклонить».\n\n"
            "Вводить в игре ничего не нужно. Если сообщение не пришло — "
            "повторите вход в Minecraft, запрос придёт заново."
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

        # Кнопки убираем: решение уже принято, и нажимать больше нечего.
        await self._edit(query, text, None)
        await query.answer()

    @staticmethod
    def _approval_outcome_text(outcome: str, approve: bool) -> str:
        if outcome == "APPLIED":
            return (
                "✅ Вход подтверждён." if approve
                else "❌ Вход отклонён. Если это были не вы — смените пароль."
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
            "🔐 <b>" + esc(lines[0]) + "</b>\n\n"
            + "\n".join(esc(x) for x in lines[1:]),
            menu.approval_keyboard(message.get("approvalId")),
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


NOTICES = {
    "AccountLoginEvent": "🔓 Вход в аккаунт {username}\nАдрес: {ip}\n{at}",
    "PasswordChangedEvent": "🔑 Пароль изменён.\n{at}\n\nЕсли это были не вы — "
                            "срочно обратитесь к администрации.",
    "SuspiciousActivityEvent": "⚠️ Подозрительная активность в аккаунте {username}.\n"
                               "Адрес: {ip}\n{at}",
    "SessionInvalidatedEvent": "🚪 Сессии аккаунта завершены.\n{at}",
    "BindingChangedEvent": "🔗 Привязки аккаунта изменены.\n{at}",
}
