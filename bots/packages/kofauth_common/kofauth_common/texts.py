"""Тексты ботов и форматирование данных.

Собраны в одном месте по той же причине, что и ``messages.yml`` на стороне
сервера: правки формулировок не должны требовать чтения кода обработчиков.
Разметка не подставляется — она разная у Telegram (HTML) и Discord (Markdown),
поэтому здесь только текст, а оформление добавляет платформенный слой.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

# ------------------------------------------------------------------- бренд
#
# В игре бренд собирается градиентом MiniMessage: KoF красный, переходящий в
# золото, Network белый. Ни Telegram, ни Discord градиентов в тексте не умеют —
# у обоих разметка ограничена жирным, курсивом и моноширинным. Поэтому здесь
# бренд опознаётся не цветом, а формой: одно слово, всегда одинаковое
# написание, всегда с одним и тем же значком.

BRAND = "KoFNetwork"

#: Значок сети. Ставится перед названием везде, где оно появляется: это
#: единственная замена цвету, доступная в мессенджерах.
BRAND_MARK = "🔥"

BRAND_TITLE = f"{BRAND_MARK} {BRAND}"

#: Значки разделов. Собраны в одном месте, чтобы кнопка и заголовок экрана,
#: на который она ведёт, не разъезжались: в Telegram они задаются в разных
#: файлах, и без общего источника один из них рано или поздно отстаёт.
ICON = {
    "profile": "👤",
    "security": "🛡",
    "history": "🕘",
    "sessions": "🔑",
    "help": "❓",
    "link": "🔗",
    "unlink": "🔓",
    "logout": "🚪",
    "donate": "💎",
    "approve": "✅",
    "deny": "❌",
    "bell_on": "🔔",
    "bell_off": "🔕",
    "back": "◀",
    "warning": "⚠️",
    "lock": "🔐",
    "key": "🔑",
    "site": "🌐",
}

#: Цвет полосы у эмбедов Discord — красный KoF. Единственное место, где
#: фирменный цвет вообще доступен в мессенджере.
BRAND_COLOUR = 0xFF2D2D
DANGER_COLOUR = 0xE8505B
ATTENTION_COLOUR = 0xFFB020

# --------------------------------------------------------------------- общее

NOT_LINKED = (
    "Аккаунт не привязан.\n\n"
    "Зайдите в игру, наберите {command} и пришлите мне полученный код."
)
API_UNAVAILABLE = "Сервер сейчас недоступен. Попробуйте через минуту."
INTERNAL_ERROR = "Не получилось. Попробуйте позже."

LINK_ERRORS = {
    "CODE_INVALID": "Код недействителен или истёк. Возьмите новый в игре.",
    "TELEGRAM_ALREADY_LINKED": "Этот Telegram уже привязан к другому аккаунту.",
    "DISCORD_ALREADY_LINKED": "Этот Discord уже привязан к другому аккаунту.",
    "ALREADY_LINKED": "К аккаунту уже привязан мессенджер. Сначала отвяжите его.",
    "NOT_LINKED": "Аккаунт не привязан.",
    "RATE_LIMITED": "Слишком часто. Подождите немного.",
}


def describe_error(code: str) -> str:
    """Человеческое объяснение кода ошибки."""
    return LINK_ERRORS.get(code, INTERNAL_ERROR)


# ------------------------------------------------------------------ значения


def format_time(raw: str | None) -> str:
    """ISO-время в вид, читаемый человеком.

    Неразобранное значение возвращается как есть: показать сырую строку лучше,
    чем скрыть факт наличия данных за прочерком.
    """
    if not raw:
        return "никогда"
    try:
        moment = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return raw
    return moment.strftime("%d.%m.%Y %H:%M UTC")


def format_location(country: str | None, city: str | None) -> str:
    if not country and not city:
        return "неизвестно"
    if not city:
        return str(country)
    if not country:
        return str(city)
    return f"{country}, {city}"


def format_two_factor(profile: dict[str, Any]) -> str:
    if profile.get("totpEnabled"):
        return "приложение-аутентификатор"
    methods = profile.get("twoFactor") or []
    if not methods:
        return "выключен"
    names = {
        "TOTP": "приложение",
        "TELEGRAM": "Telegram",
        "DISCORD": "Discord",
        "EMAIL": "почта",
    }
    return ", ".join(names.get(str(m), str(m)) for m in methods)


def yes_no(value: Any) -> str:
    return "включено" if value else "выключено"


# ------------------------------------------------------------------- экраны


def profile_lines(profile: dict[str, Any], donate_url: str = "") -> list[str]:
    """Строки экрана «Профиль» без платформенной разметки.

    Показывается ровно то, что игроку о себе интересно: как его зовут, когда
    он завёл аккаунт, когда заходил в последний раз и чем защищён.

    UUID, статус, адрес и расположение убраны намеренно. UUID и статус игроку
    ничего не говорят — это наши служебные поля. Адрес и город — данные, из-за
    которых экран профиля нельзя показать другу, не открыв заодно, откуда ты
    выходишь в сеть; при этом ни то, ни другое ничем не помогает: адрес входа
    виден в «Истории», а разбор чужого входа — задача администратора, у него
    для этого есть /auth player.

    :param donate_url: если задан, в конце появляется строка поддержки сервера
    """
    lines = [
        f"Ник: {profile.get('username', '—')}",
        f"Регистрация: {format_time(profile.get('registeredAt'))}",
        f"Последний вход: {format_time(profile.get('lastLoginAt'))}",
        f"Второй фактор: {format_two_factor(profile)}",
    ]
    if profile.get("temporarilyLocked"):
        lines.append(
            f"{ICON['warning']} Временная блокировка до: "
            f"{format_time(profile.get('lockedUntil'))}"
        )
    if donate_url:
        lines.extend(["", f"{ICON['donate']} Поддержать сервер: {donate_url}"])
    return lines


def security_lines(profile: dict[str, Any]) -> list[str]:
    return [
        f"Подтверждение входа кнопкой: {yes_no(profile.get('loginApproval'))}",
        f"Уведомления: {yes_no(profile.get('notifications'))}",
        f"Второй фактор: {format_two_factor(profile)}",
        f"CAPTCHA пройдена: {'да' if profile.get('captchaPassed') else 'нет'}",
    ]


def history_lines(history: list[dict[str, Any]]) -> list[str]:
    if not history:
        return ["История пуста."]
    lines = []
    for entry in history:
        mark = "✅" if entry.get("success") else "❌"
        location = format_location(entry.get("country"), entry.get("city"))
        reason = "" if entry.get("success") else f" · {entry.get('result') or ''}"
        lines.append(
            f"{mark} {format_time(entry.get('at'))}\n"
            f"  {entry.get('ip', '—')} · {location}{reason}"
        )
    return lines


def session_lines(sessions: list[dict[str, Any]]) -> list[str]:
    if not sessions:
        return ["Активных сессий нет."]
    return [
        f"• {session.get('type', '—')} · {session.get('ip', '—')}\n"
        f"  {session.get('server') or 'вне сервера'} · "
        f"{format_time(session.get('lastSeenAt'))}"
        for session in sessions
    ]


def approval_request_lines(username: str, ip: str, location: str) -> list[str]:
    """Запрос подтверждения входа.

    Адрес и расположение здесь остаются, хотя из профиля убраны, и это не
    противоречие: в профиле они отвечали на вопрос «где я живу», а тут — на
    вопрос «я ли это сейчас захожу». Без них у владельца нет ничего, по чему
    отличить свой вход от чужого, и кнопка «Войти» превращается в формальность.
    """
    return [
        f"Вход в аккаунт {username}",
        "",
        f"Адрес: {ip}",
        f"Расположение: {location}",
        "",
        "Это вы?",
    ]


def link_code_lines(username: str, code: str, ttl: str) -> list[str]:
    """Сообщение о выданном коде для служебного канала привязки."""
    return [
        f"Игрок: {username}",
        f"Код: {code}",
        "",
        f"Отправьте боту /link {code} — код действует {ttl}"
        " и сгорает после первого использования.",
    ]
