"""Тексты ботов и форматирование данных.

Собраны в одном месте по той же причине, что и ``messages.yml`` на стороне
сервера: правки формулировок не должны требовать чтения кода обработчиков.
Разметка не подставляется — она разная у Telegram (HTML) и Discord (Markdown),
поэтому здесь только текст, а оформление добавляет платформенный слой.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

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


def profile_lines(profile: dict[str, Any]) -> list[str]:
    """Строки экрана «Профиль» без платформенной разметки."""
    lines = [
        f"Ник: {profile.get('username', '—')}",
        f"UUID: {profile.get('uuid', '—')}",
        f"Статус: {profile.get('status', '—')}",
        f"Регистрация: {format_time(profile.get('registeredAt'))}",
        f"Последний вход: {format_time(profile.get('lastLoginAt'))}",
        f"Адрес: {profile.get('lastLoginIp') or '—'}",
        f"Расположение: {format_location(profile.get('country'), profile.get('city'))}",
        f"Второй фактор: {format_two_factor(profile)}",
    ]
    if profile.get("temporarilyLocked"):
        lines.append(f"Временная блокировка до: {format_time(profile.get('lockedUntil'))}")
    return lines


def security_lines(profile: dict[str, Any]) -> list[str]:
    return [
        f"Подтверждение входа кнопкой: {yes_no(profile.get('loginApproval'))}",
        f"Уведомления: {yes_no(profile.get('notifications'))}",
        f"Второй фактор: {format_two_factor(profile)}",
        f"CAPTCHA пройдена: {'да' if profile.get('captchaPassed') else 'нет'}",
    ]


def device_lines(devices: list[dict[str, Any]]) -> list[str]:
    if not devices:
        return ["Устройств пока нет."]
    lines = []
    for device in devices:
        marks = []
        if device.get("trusted"):
            marks.append("доверенное")
        if device.get("blocked"):
            marks.append("заблокировано")
        suffix = f" · {', '.join(marks)}" if marks else ""
        lines.append(
            f"• {device.get('name', 'устройство')}\n"
            f"  {device.get('ip', '—')} · {format_time(device.get('lastSeenAt'))}{suffix}"
        )
    return lines


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
    return [
        f"Вход в аккаунт {username}",
        "",
        f"Адрес: {ip}",
        f"Расположение: {location}",
        "",
        "Это вы?",
    ]
