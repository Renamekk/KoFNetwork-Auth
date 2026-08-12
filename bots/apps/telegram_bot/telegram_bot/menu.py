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
from kofauth_common.texts import ICON

# Префикс отделяет навигацию от кнопок подтверждения входа: обработчик
# подтверждения не должен реагировать на переход по меню и наоборот.
MENU = "menu"
ACTION = "act"

HOME = "home"
PROFILE = "profile"
SECURITY = "security"
HISTORY = "history"
SESSIONS = "sessions"
HELP = "help"


def _button(text: str, screen: str) -> InlineKeyboardButton:
    return InlineKeyboardButton(text=text, callback_data=f"{MENU}:{screen}")


def _action(text: str, name: str) -> InlineKeyboardButton:
    return InlineKeyboardButton(text=text, callback_data=f"{ACTION}:{name}")


def main_menu(linked: bool, donate_url: str = "") -> InlineKeyboardMarkup:
    """Главный экран.

    До привязки показывать «История» и «Сессии» бессмысленно: нажатие привело бы
    к одному и тому же ответу «аккаунт не привязан». Поэтому у непривязанного
    человека ровно один осмысленный путь.

    Раздела «Устройства» здесь больше нет: он показывал те же адреса, что и
    «История», только в другом порядке, и ни одной кнопки к ним не прилагалось —
    смотреть список, с которым нечего сделать, незачем.
    """
    builder = InlineKeyboardBuilder()
    if not linked:
        builder.row(_button(f"{ICON['link']} Как привязать аккаунт", HELP))
        if donate_url:
            builder.row(_link(f"{ICON['donate']} Поддержать сервер", donate_url))
        return builder.as_markup()

    builder.row(
        _button(f"{ICON['profile']} Профиль", PROFILE),
        _button(f"{ICON['security']} Защита", SECURITY),
    )
    builder.row(
        _button(f"{ICON['history']} История", HISTORY),
        _button(f"{ICON['sessions']} Сессии", SESSIONS),
    )
    builder.row(_button(f"{ICON['help']} Справка", HELP))
    if donate_url:
        builder.row(_link(f"{ICON['donate']} Поддержать сервер", donate_url))
    return builder.as_markup()


def _link(text: str, url: str) -> InlineKeyboardButton:
    """Кнопка-ссылка. Telegram открывает её сам, обработчик не нужен."""
    return InlineKeyboardButton(text=text, url=url)


def back_only() -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.row(_button(f"{ICON['back']} Назад", HOME))
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
            f"{ICON['bell_off']} Выключить подтверждение" if login_approval
            else f"{ICON['bell_on']} Включить подтверждение",
            "approval:off" if login_approval else "approval:on",
        )
    )
    builder.row(_action(f"{ICON['logout']} Выйти со всех устройств", "logout"))
    builder.row(_action(f"{ICON['unlink']} Отвязать Telegram", "unlink:ask"))
    builder.row(_button(f"{ICON['back']} Назад", HOME))
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


def approval_keyboard(approval_id: str) -> InlineKeyboardMarkup:
    """Кнопки подтверждения входа.

    Ровно две и никаких кодов. В ``callback_data`` едет идентификатор запроса —
    не секрет: сам по себе он ничего не открывает, потому что сервер сверяет
    ещё и того, кто нажал. Прежде здесь был предъявительский токен, и его
    достаточно было прочитать, чтобы войти.

    Идентификатор хранится в кнопке, а не в памяти бота: перезапуск контейнера
    не должен превращать уже показанные кнопки в неработающие.
    """
    builder = InlineKeyboardBuilder()
    builder.row(
        InlineKeyboardButton(
            text=f"{ICON['approve']} Войти", callback_data=f"ok:{approval_id}"
        ),
        InlineKeyboardButton(
            text=f"{ICON['deny']} Отклонить", callback_data=f"no:{approval_id}"
        ),
    )
    return builder.as_markup()
