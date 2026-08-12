"""Меню Telegram-бота.

Навигация построена на одном сообщении, которое перерисовывается, а не на
цепочке новых. Так у человека всегда один экран вместо ленты из двадцати
ответов, а кнопки прошлых шагов не остаются нажимаемыми.

Данные кнопок — короткие строки вида ``menu:profile``. Telegram ограничивает
``callback_data`` 64 байтами, поэтому в неё кладётся имя экрана, а не состояние.

Оформление
----------

Красить кнопки Telegram нельзя: у всех один цвет темы. Поэтому иерархия
держится на трёх вещах, и все три обязаны соблюдаться в каждой раскладке:

1. **Порядок строк.** Сначала главное действие экрана, затем обычная
   навигация, затем опасное, и только потом возврат.
2. **Ширина.** Главное действие и возврат занимают строку целиком, парная
   навигация идёт по две кнопки в строке. Кнопка во всю ширину читается как
   более важная — это единственный доступный здесь «акцент».
3. **Значок.** Один на кнопку, из общего набора ``glyphs.FALLBACK``. Возврат
   всегда ``◀️``, опасное — с открытым замком.

Надпись кнопки в Bot API — голый текст: сущности к ней не прикладываются, и
фирменный custom emoji показать там нечем. В тексте сообщений он доступен, этим
занимается ``bot.py``.
"""

from __future__ import annotations

from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup
from aiogram.utils.keyboard import InlineKeyboardBuilder
from kofauth_common.glyphs import DEFAULT, EmojiSet

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


def _label(emoji: EmojiSet, key: str, text: str) -> str:
    """Надпись кнопки: значок, пробел, слово. Всегда в этом порядке."""
    return f"{emoji.telegram(key)} {text}"


def _button(text: str, screen: str) -> InlineKeyboardButton:
    return InlineKeyboardButton(text=text, callback_data=f"{MENU}:{screen}")


def _action(text: str, name: str) -> InlineKeyboardButton:
    return InlineKeyboardButton(text=text, callback_data=f"{ACTION}:{name}")


def main_menu(linked: bool, donate_url: str = "",
              emoji: EmojiSet = DEFAULT) -> InlineKeyboardMarkup:
    """Главный экран.

    До привязки показывать «История» и «Сессии» бессмысленно: нажатие привело бы
    к одному и тому же ответу «аккаунт не привязан». Поэтому у непривязанного
    человека ровно один осмысленный путь — и он занимает строку целиком.

    Раздела «Устройства» здесь больше нет: он показывал те же адреса, что и
    «История», только в другом порядке, и ни одной кнопки к ним не прилагалось —
    смотреть список, с которым нечего сделать, незачем.
    """
    builder = InlineKeyboardBuilder()
    if not linked:
        builder.row(_button(_label(emoji, "link", "Как привязать аккаунт"), HELP))
        if donate_url:
            builder.row(_link(_label(emoji, "donate", "Поддержать сервер"), donate_url))
        return builder.as_markup()

    builder.row(
        _button(_label(emoji, "profile", "Профиль"), PROFILE),
        _button(_label(emoji, "security", "Защита"), SECURITY),
    )
    builder.row(
        _button(_label(emoji, "history", "История"), HISTORY),
        _button(_label(emoji, "sessions", "Сессии"), SESSIONS),
    )
    builder.row(_button(_label(emoji, "help", "Справка"), HELP))
    if donate_url:
        builder.row(_link(_label(emoji, "donate", "Поддержать сервер"), donate_url))
    return builder.as_markup()


def _link(text: str, url: str) -> InlineKeyboardButton:
    """Кнопка-ссылка. Telegram открывает её сам, обработчик не нужен."""
    return InlineKeyboardButton(text=text, url=url)


def back_only(emoji: EmojiSet = DEFAULT) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    builder.row(_button(_label(emoji, "back", "Назад"), HOME))
    return builder.as_markup()


def security_menu(login_approval: bool, emoji: EmojiSet = DEFAULT) -> InlineKeyboardMarkup:
    """Экран защиты.

    Надпись на кнопке описывает действие, а не текущее состояние: «Выключить»
    рядом со словом «включено» читается однозначно, а «Включено» в роли кнопки
    заставляет гадать, что произойдёт по нажатию.

    Порядок строк — от главного к опасному: переключатель, обычное действие,
    отвязка, возврат. Отвязка стоит отдельной строкой не для красоты: она
    выключает второй фактор, и промах по соседней кнопке не должен в неё
    попадать.
    """
    builder = InlineKeyboardBuilder()
    builder.row(
        _action(
            _label(emoji, "bell_off", "Выключить подтверждение") if login_approval
            else _label(emoji, "bell_on", "Включить подтверждение"),
            "approval:off" if login_approval else "approval:on",
        )
    )
    builder.row(_action(_label(emoji, "logout", "Выйти со всех устройств"), "logout"))
    builder.row(_action(_label(emoji, "unlink", "Отвязать Telegram"), "unlink:ask"))
    builder.row(_button(_label(emoji, "back", "Назад"), HOME))
    return builder.as_markup()


def confirm_unlink(emoji: EmojiSet = DEFAULT) -> InlineKeyboardMarkup:
    """Отвязка спрашивает подтверждение.

    Она выключает второй фактор и уведомления разом — то есть тихо снижает
    защиту аккаунта. Такое действие не должно совершаться одним промахом
    по кнопке.

    Значки разводят два ответа сильнее, чем слова: открытый замок у согласия и
    стрелка возврата у отказа. Слева согласие, справа отмена — тот же порядок,
    что и у подтверждения входа, чтобы привычка не подводила.
    """
    builder = InlineKeyboardBuilder()
    builder.row(
        _action(_label(emoji, "unlink", "Да, отвязать"), "unlink:yes"),
        _button(_label(emoji, "back", "Отмена"), SECURITY),
    )
    return builder.as_markup()


def approval_keyboard(approval_id: str, emoji: EmojiSet = DEFAULT) -> InlineKeyboardMarkup:
    """Кнопки подтверждения входа.

    Ровно две и никаких кодов. В ``callback_data`` едет идентификатор запроса —
    не секрет: сам по себе он ничего не открывает, потому что сервер сверяет
    ещё и того, кто нажал. Прежде здесь был предъявительский токен, и его
    достаточно было прочитать, чтобы войти.

    Идентификатор хранится в кнопке, а не в памяти бота: перезапуск контейнера
    не должен превращать уже показанные кнопки в неработающие.

    Значки выбраны так, чтобы два ответа не спутались при беглом взгляде: ключ
    отпирает вход, красный крест его закрывает.
    """
    builder = InlineKeyboardBuilder()
    builder.row(
        InlineKeyboardButton(
            text=_label(emoji, "approve", "Войти"), callback_data=f"ok:{approval_id}"
        ),
        InlineKeyboardButton(
            text=_label(emoji, "deny", "Отклонить"), callback_data=f"no:{approval_id}"
        ),
    )
    return builder.as_markup()
