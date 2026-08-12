"""Подтверждение входа кнопкой.

Проверяется то, чего не было в прежнем механизме: нажатие уезжает вместе с
платформой и идентификатором нажавшего, а исходы — повтор, истечение, отказ и
чужое нажатие — различимы и объясняются человеку по-разному.

Прежде бот отправлял на сервер один предъявительский токен: кто его прочитал,
тот и входил, а «уже обработано», «истекло» и «не ваша кнопка» сливались в одно
«запрос устарел».
"""

from __future__ import annotations

import json

import httpx
import respx
from discord_bot.views import approval_outcome_text
from kofauth_common.api import ApiUnavailable, KoFAuthApi
from kofauth_common.config import ApiSettings
from telegram_bot.bot import TelegramBot

BASE = "http://webapi:8080"


def make_api() -> KoFAuthApi:
    return KoFAuthApi(ApiSettings(base_url=BASE, bot_key="secret"))


class TestЗапросНаСервер:
    @respx.mock
    async def test_нажатие_везёт_платформу_и_нажавшего(self) -> None:
        # Идентификатор нажавшего подставляет бот из данных мессенджера, а не
        # человек: именно по нему сервер сверяет, тому ли адресована кнопка.
        route = respx.post(f"{BASE}/api/bot/approval").mock(
            return_value=httpx.Response(200, json={"ok": True, "result": "APPLIED"})
        )

        async with make_api() as api:
            result = await api.approve("DISCORD", 2002, "запрос-1", True)

        assert result.ok
        assert json.loads(route.calls.last.request.content) == {
            "platform": "DISCORD",
            "externalId": 2002,
            "approvalId": "запрос-1",
            "approved": True,
        }

    @respx.mock
    async def test_чужое_нажатие_возвращается_значением(self) -> None:
        # 403 — не сбой транспорта, а обычный исход: боту нужно показать
        # человеку, что кнопка не его.
        respx.post(f"{BASE}/api/bot/approval").mock(
            return_value=httpx.Response(403, json={"result": "FOREIGN",
                                                   "code": "FOREIGN"})
        )

        async with make_api() as api:
            result = await api.approve("TELEGRAM", 999, "запрос-1", True)

        assert not result.ok
        assert result.data.get("result") == "FOREIGN"

    @respx.mock
    async def test_повтор_и_истечение_различимы(self) -> None:
        respx.post(f"{BASE}/api/bot/approval").mock(
            return_value=httpx.Response(409, json={"result": "ALREADY_DECIDED",
                                                   "status": "APPROVED"})
        )

        async with make_api() as api:
            result = await api.approve("TELEGRAM", 1001, "запрос-1", True)

        assert not result.ok
        assert result.data.get("result") == "ALREADY_DECIDED"

    @respx.mock
    async def test_недоступный_api_поднимает_исключение(self) -> None:
        # Сеть отвалилась — это «ответа нет», а не «кнопка не сработала».
        # Показывать человеку «запрос устарел» было бы враньём, и кнопку
        # убирать нельзя: запрос, возможно, ещё жив.
        respx.post(f"{BASE}/api/bot/approval").mock(side_effect=httpx.ConnectError("нет сети"))

        async with make_api() as api:
            try:
                await api.approve("TELEGRAM", 1001, "запрос-1", True)
            except ApiUnavailable:
                return
        raise AssertionError("ожидалось ApiUnavailable")


class TestТекстыИсходов:
    """Каждый исход объясняется отдельно — иначе человек не поймёт, что делать."""

    def test_discord_различает_все_исходы(self) -> None:
        assert approval_outcome_text("APPLIED", True) == "Вход подтверждён."
        assert "смените пароль" in approval_outcome_text("APPLIED", False)
        assert approval_outcome_text("ALREADY_DECIDED", True) == "Этот запрос уже обработан."
        assert "истекло" in approval_outcome_text("EXPIRED", True)
        assert "другому человеку" in approval_outcome_text("FOREIGN", True)
        assert "не найден" in approval_outcome_text("NOT_FOUND", True)

    def test_telegram_различает_все_исходы(self) -> None:
        text = TelegramBot._approval_outcome_text

        assert "подтверждён" in text("APPLIED", True)
        assert "отклонён" in text("APPLIED", False)
        assert "уже обработан" in text("ALREADY_DECIDED", True)
        assert "истекло" in text("EXPIRED", True)
        assert "другому человеку" in text("FOREIGN", True)
        assert "не найден" in text("NOT_FOUND", True)

    def test_неизвестный_исход_не_роняет_бота(self) -> None:
        # Новый исход на стороне Core не должен приводить к исключению в чате.
        assert approval_outcome_text("НЕЧТО", True)
        assert TelegramBot._approval_outcome_text("НЕЧТО", True)


class TestКнопки:
    def test_в_telegram_ровно_две_кнопки_без_кодов(self) -> None:
        from kofauth_common.glyphs import FALLBACK
        from telegram_bot import menu

        markup = menu.approval_keyboard("запрос-1")
        buttons = [button for row in markup.inline_keyboard for button in row]

        assert len(buttons) == 2
        # Значок проверяется по общему набору, а не по символу: оформление
        # меняется, а число действий и их смысл — нет.
        assert [button.text for button in buttons] == [
            f"{FALLBACK['approve']} Войти", f"{FALLBACK['deny']} Отклонить",
        ]
        assert {button.callback_data for button in buttons} == {
            "ok:запрос-1", "no:запрос-1",
        }

    def test_callback_data_умещается_в_ограничение_telegram(self) -> None:
        # Telegram отвергает callback_data длиннее 64 байт. Идентификатор
        # запроса — 16 байт в base64url, префикс «ok:» добавляет три знака.
        from telegram_bot import menu

        markup = menu.approval_keyboard("A" * 22)
        buttons = [button for row in markup.inline_keyboard for button in row]

        for button in buttons:
            assert len(button.callback_data.encode("utf-8")) <= 64
