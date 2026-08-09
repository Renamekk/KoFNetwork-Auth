"""Меню Telegram-бота.

Навигация построена на одном сообщении, которое перерисовывается, а не на
цепочке новых. Так у человека всегда один экран вместо ленты из двадцати
ответов, а кнопки прошлых шагов не остаются нажимаемыми.

Данные кнопок — короткие строки вида ``menu:profile``. Telegram ограничивает
``callback_data`` 64 байтами, поэтому в неё кладётся имя экрана, а не состояние.
"""

from __future__ import annotations

from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup
from aiogram.utils.keyboard import InlineKeyboardBuilder

# Префикс отделяет навигацию от кнопок подтверждения входа: обработчик
# подтверждения не должен реагировать на переход по меню и наоборот.
MENU = "menu"
ACTION = "act"

HOME = "home"
PROFILE = "profile"
SECURITY = "security"
DEVICES = "devices"
HISTORY = "history"
SESSIONS = "sessions"
HELP = "help"


def _button(text: str, screen: str) -> InlineKeyboardButton:
    return InlineKeyboardButton(text=text, callback_data=f"{MENU}:{screen}")


def _action(text: str, name: str) -> InlineKeyboardButton:
    return InlineKeyboardButton(text=text, callback_data=f"{ACTION}:{name}")


def main_menu(linked: bool) -> InlineKeyboardMarkup:
    """Главный экран.

    До привязки показывать «Устройства» и «История» бессмысленно: нажатие
    привело бы к одному и тому же ответу «аккаунт не привязан». Поэтому у
    непривязанного человека ровно один осмысленный путь.
    """
    builder = InlineKeyboardBuilder()
    if not linked:
        builder.row(_button("🔗 Как привязать аккаунт", HELP))
        return builder.as_markup()

    builder.row(_button("👤 Профиль", PROFILE), _button("🛡 Защита", SECURITY))
    builder.row(_button("💻 Устройства", DEVICES), _button("🕘 История", HISTORY))
    builder.row(_button("🔑 Сессии", SESSIONS))
    builder.row(_action("🔢 Код для входа", "sendcode"))
    builder.row(_button("❓ Справка", HELP))
    return builder.as_markup()


def back_only() -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.row(_button("‹ Назад", HOME))
    return builder.as_markup()


def security_menu(login_approval: bool) -> InlineKeyboardMarkup:
    """Экран защиты.

    Надпись на кнопке описывает действие, а не текущее состояние: «Выключить»
    рядом со словом «включено» читается однозначно, а «Включено» в роли кнопки
    заставляет гадать, что произойдёт по нажатию.
    """
    builder = InlineKeyboardBuilder()
    builder.row(
        _action(
            "🔕 Выключить подтверждение" if login_approval else "🔔 Включить подтверждение",
            "approval:off" if login_approval else "approval:on",
        )
    )
    builder.row(_action("🚪 Выйти со всех устройств", "logout"))
    builder.row(_action("🔓 Отвязать Telegram", "unlink:ask"))
    builder.row(_button("‹ Назад", HOME))
    return builder.as_markup()


def confirm_unlink() -> InlineKeyboardMarkup:
    """Отвязка спрашивает подтверждение.

    Она выключает второй фактор и уведомления разом — то есть тихо снижает
    защиту аккаунта. Такое действие не должно совершаться одним промахом
    по кнопке.
    """
    builder = InlineKeyboardBuilder()
    builder.row(
        _action("Да, отвязать", "unlink:yes"),
        _button("Отмена", SECURITY),
    )
    return builder.as_markup()


def approval_keyboard(token: str) -> InlineKeyboardMarkup:
    """Кнопки подтверждения входа.

    Токен едет прямо в ``callback_data``: он одноразовый, живёт две минуты и
    гасится сервером при первом нажатии. Хранить его в памяти бота значило бы
    терять запросы при перезапуске контейнера.
    """
    builder = InlineKeyboardBuilder()
    builder.row(
        InlineKeyboardButton(text="✅ Это я", callback_data=f"ok:{token}"),
        InlineKeyboardButton(text="❌ Это не я", callback_data=f"no:{token}"),
    )
    return builder.as_markup()
