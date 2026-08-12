"""Значки кнопок KoF Network и фирменные emoji.

Оформление кнопок собрано здесь по той же причине, что и тексты: значок на
кнопке и значок в заголовке экрана, на который она ведёт, задаются в разных
файлах и разных ботах. Без общего источника один из них рано или поздно
отстаёт от другого.

Палитра сети — чёрное, белое и красный ``#FF2D2D``. Ни Telegram, ни Discord не
дают красить кнопки произвольно: у Telegram кнопки вообще одного цвета, у
Discord их ровно четыре стиля. Поэтому фирменность держится на трёх вещах:

1. **Цвет там, где он есть.** В Discord красный — это ``danger``, а ближайший
   аналог белого — ``secondary``. Синий ``primary`` и зелёный ``success`` не
   используются вовсе: три чужих цвета рядом с красным превращают меню в набор
   разноцветных кнопок без иерархии.
2. **Одинаковый набор значков.** Один значок на кнопку, один и тот же на обеих
   платформах, без «ещё одного смайлика для красоты».
3. **Раскладка.** Возврат всегда последней строкой и один, опасное действие —
   на отдельной строке.

Значки подобраны монохромными или красными: жёлтые и зелёные пятна рядом с
красным читаются как отдельные состояния, а не как один интерфейс.

Фирменные emoji
---------------

Готовые картинки сети подключаются через окружение и **не придумываются в
коде**: идентификатор emoji выдаёт платформа, выдумать его нельзя.

* ``KOFAUTH_EMOJI_DISCORD`` — ``ключ=<:имя:id>`` (или ``имя:id``).
* ``KOFAUTH_EMOJI_TELEGRAM`` — ``ключ=id`` документа custom emoji.

Любой платформе можно вместо этого подсунуть просто другой символ:
``ключ=🧱``. Что не задано — показывается значком по умолчанию из
:data:`FALLBACK`, поэтому пустая настройка это не «сломанный интерфейс», а
обычный рабочий вид.
"""

from __future__ import annotations

import logging
import re

LOGGER = logging.getLogger(__name__)

#: Значки по умолчанию. Это и есть рабочий вид интерфейса: фирменные emoji —
#: необязательное улучшение, а не условие работы.
#:
#: Ключи описывают роль, а не картинку («logout», а не «дверь»), чтобы замена
#: символа не требовала правок в ботах.
FALLBACK: dict[str, str] = {
    # Бренд. Красный блок — это ровно фирменный цвет и ровно пиксель Minecraft;
    # в мессенджерах это единственный способ показать цвет в тексте.
    "brand": "🟥",
    # Разделы. Нейтральные, монохромные: они не действия, а навигация.
    "profile": "👤",
    "security": "🛡️",
    "history": "🕘",
    "sessions": "🖥️",
    "help": "❓",
    "commands": "⌨️",
    # Действия.
    "link": "🔗",
    "unlink": "🔓",
    "logout": "🚪",
    "donate": "❤️",
    # «Войти» — это отпирание доступа, а не «галочка». Зелёная галочка на
    # красной кнопке подтверждения входа спорила бы и с кнопкой, и с палитрой.
    "approve": "🔑",
    "deny": "❌",
    "bell_on": "🔔",
    "bell_off": "🔕",
    "back": "◀️",
    # Состояния в тексте сообщений — не кнопки. Здесь галочка и крест на своём
    # месте: это отметки об исходе, их читают построчно.
    "ok": "✅",
    "warning": "⚠️",
    "lock": "🔐",
    "site": "🌐",
}

#: ``<:имя:id>`` и ``<a:имя:id>`` — то, что кладёт в буфер сам Discord.
#: Идентификаторы там снежинки, 17–20 цифр; диапазон взят с запасом.
_DISCORD_FULL = re.compile(r"^<(a?):([A-Za-z0-9_]{2,32}):(\d{15,25})>$")

#: ``имя:id`` — та же запись без угловых скобок: их легко потерять при
#: переносе в ``.env``, и отвергать из-за этого настройку незачем.
_DISCORD_SHORT = re.compile(r"^([A-Za-z0-9_]{2,32}):(\d{15,25})$")

#: ``id`` или ``id:символ`` — идентификатор документа custom emoji и запасной
#: символ к нему.
_TELEGRAM_CUSTOM = re.compile(r"^(\d{5,25})(?::(.+))?$")

_PAIRS = re.compile(r"[,;\n]+")

#: Разметка Telegram для custom emoji. Внутри тега обязан лежать обычный
#: символ: его увидят те, у кого фирменный emoji не отобразился.
_TG_EMOJI = '<tg-emoji emoji-id="{ident}">{fallback}</tg-emoji>'
_TG_EMOJI_TAG = re.compile(r'<tg-emoji emoji-id="\d{1,25}">(.*?)</tg-emoji>', re.DOTALL)


def strip_custom_emoji(html_text: str) -> str:
    """Убирает теги фирменных emoji, оставляя запасные символы.

    Нужно на случай, когда Telegram отказывается принимать custom emoji: право
    на них есть не у каждого бота, и узнаётся это только в ответе на отправку.
    Тег снимается вместе с идентификатором, а текст остаётся тем же — человек
    видит обычный значок вместо фирменного, а не пустое место.
    """
    return _TG_EMOJI_TAG.sub(r"\1", html_text)


def _discord_custom(value: str) -> str | None:
    """Приводит запись фирменного emoji Discord к виду ``<:имя:id>``.

    Две формы, а не одна с необязательными скобками: в ``<a:имя:id>`` буква
    ``a`` значит «анимированный», а в ``a_имя:id`` она часть имени, и одним
    выражением эти два случая не различить.
    """
    full = _DISCORD_FULL.match(value)
    if full:
        animated, name, ident = full.groups()
        return f"<{animated}:{name}:{ident}>"
    short = _DISCORD_SHORT.match(value)
    if short:
        name, ident = short.groups()
        return f"<:{name}:{ident}>"
    return None


def _looks_like_symbol(value: str) -> bool:
    """Похоже ли значение на просто символ, а не на испорченный идентификатор.

    Проверка грубая намеренно: перечислять диапазоны emoji означало бы
    отвергать всё, что Unicode добавит после нас. Достаточно отсечь то, что
    заведомо является опечаткой в ``имя:id``.
    """
    if not value or len(value) > 8:
        return False
    return not any(char.isascii() and (char.isalnum() or char in "<>:=") for char in value)


def _entries(raw: str) -> list[tuple[str, str]]:
    """Разбирает ``ключ=значение`` через запятую, отбрасывая мусор.

    Ошибка в оформлении не должна ронять бота: непонятная запись пропускается с
    предупреждением, и кнопка показывается значком по умолчанию. В журнал идёт
    только ключ — значение туда класть незачем.
    """
    result: list[tuple[str, str]] = []
    for chunk in _PAIRS.split(raw or ""):
        chunk = chunk.strip()
        if not chunk:
            continue
        key, separator, value = chunk.partition("=")
        key, value = key.strip().lower(), value.strip()
        if not separator or not value:
            LOGGER.warning("Значок пропущен: запись не вида «ключ=значение»")
            continue
        if key not in FALLBACK:
            LOGGER.warning("Значок пропущен: неизвестный ключ %r", key)
            continue
        result.append((key, value))
    return result


class EmojiSet:
    """Набор значков одного развёртывания.

    Хранит три вещи на платформу: фирменный emoji, если он задан, запасной
    символ, если его переопределили, и общий словарь по умолчанию.

    Объект неизменяемый — :meth:`without_telegram_custom` возвращает новый
    набор, а не правит этот. Так откат на обычные значки не может задеть уже
    отправленные сообщения или другой поток.
    """

    __slots__ = ("_discord", "_discord_symbols", "_telegram", "_telegram_symbols")

    def __init__(self, discord_raw: str = "", telegram_raw: str = "") -> None:
        self._discord: dict[str, str] = {}
        self._discord_symbols: dict[str, str] = {}
        self._telegram: dict[str, str] = {}
        self._telegram_symbols: dict[str, str] = {}

        for key, value in _entries(discord_raw):
            custom = _discord_custom(value)
            if custom:
                self._discord[key] = custom
            elif _looks_like_symbol(value):
                self._discord_symbols[key] = value
            else:
                LOGGER.warning("Значок %r пропущен: не «<:имя:id>» и не символ", key)

        for key, value in _entries(telegram_raw):
            match = _TELEGRAM_CUSTOM.match(value)
            if match:
                ident, symbol = match.groups()
                self._telegram[key] = ident
                if symbol and _looks_like_symbol(symbol):
                    self._telegram_symbols[key] = symbol
            elif _looks_like_symbol(value):
                self._telegram_symbols[key] = value
            else:
                LOGGER.warning("Значок %r пропущен: не идентификатор и не символ", key)

    # ------------------------------------------------------------------ доступ

    def symbol(self, key: str) -> str:
        """Значок по умолчанию — тот, что видят без всяких настроек."""
        return FALLBACK.get(key, "")

    def discord(self, key: str) -> str:
        """Что положить в ``emoji=`` кнопки Discord.

        Фирменный emoji — если задан и доступен боту; иначе обычный символ.
        ``discord.py`` разбирает обе формы одинаково.
        """
        return self._discord.get(key) or self._discord_symbols.get(key) or self.symbol(key)

    def telegram(self, key: str) -> str:
        """Что положить в надпись кнопки Telegram.

        Всегда обычный символ. Надпись кнопки в Bot API — голый текст: сущности
        к ней не прикладываются, и custom emoji там показать нечем. Фирменные
        emoji Telegram доступны только в тексте сообщения, см.
        :meth:`telegram_html`.
        """
        return self._telegram_symbols.get(key) or self.symbol(key)

    def telegram_html(self, key: str) -> str:
        """Значок для текста сообщения Telegram — с фирменным emoji, если задан.

        Запасной символ остаётся внутри тега: у кого фирменный emoji не
        отобразится, тот увидит обычный.
        """
        symbol = self.telegram(key)
        ident = self._telegram.get(key)
        if not ident:
            return symbol
        return _TG_EMOJI.format(ident=ident, fallback=symbol)

    @property
    def has_telegram_custom(self) -> bool:
        return bool(self._telegram)

    def without_telegram_custom(self) -> EmojiSet:
        """Тот же набор, но без фирменных emoji Telegram.

        Нужен ровно в одном случае: Telegram ответил, что бот не вправе слать
        custom emoji. Дальше показываются обычные значки — интерфейс от этого
        не меняется, кроме самих картинок.
        """
        clone = EmojiSet()
        clone._discord = dict(self._discord)
        clone._discord_symbols = dict(self._discord_symbols)
        clone._telegram_symbols = dict(self._telegram_symbols)
        return clone


#: Набор без настроек: только значки по умолчанию. Им пользуются меню, когда
#: конкретный набор не передали, — например, в тестах.
DEFAULT = EmojiSet()
