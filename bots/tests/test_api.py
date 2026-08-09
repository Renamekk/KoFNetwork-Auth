"""Клиент REST API ботов."""

from __future__ import annotations

import httpx
import pytest
import respx

from kofauth_common.api import ApiUnavailable, KoFAuthApi
from kofauth_common.config import ApiSettings, ConfigurationError

BASE = "http://webapi:8080"


def make_api() -> KoFAuthApi:
    return KoFAuthApi(ApiSettings(base_url=BASE, bot_key="secret"))


class TestConfiguration:
    def test_пустой_ключ_не_принимается(self) -> None:
        # Пустой ключ означал бы анонимные запросы к /api/bot, которые он
        # отвергает. Падать на старте честнее, чем на каждой команде игрока.
        with pytest.raises(ConfigurationError):
            KoFAuthApi(ApiSettings(base_url=BASE, bot_key=""))

    def test_пустой_адрес_не_принимается(self) -> None:
        with pytest.raises(ConfigurationError):
            KoFAuthApi(ApiSettings(base_url="", bot_key="secret"))


class TestРезультаты:
    @respx.mock
    async def test_успех_возвращает_данные(self) -> None:
        respx.get(f"{BASE}/api/bot/account").mock(
            return_value=httpx.Response(200, json={"username": "Steve"})
        )
        async with make_api() as api:
            result = await api.account("TELEGRAM", 42)

        assert result.ok
        assert result.data["username"] == "Steve"

    @respx.mock
    async def test_отказ_по_существу_это_значение_а_не_исключение(self) -> None:
        # «Код истёк» — обычный исход, который бот показывает человеку.
        # Исключение здесь заставило бы оборачивать в try каждый вызов.
        respx.post(f"{BASE}/api/bot/link").mock(
            return_value=httpx.Response(
                400, json={"code": "CODE_INVALID", "message": "истёк"}
            )
        )
        async with make_api() as api:
            result = await api.link("TELEGRAM", "ABC", 42)

        assert not result.ok
        assert result.error == "CODE_INVALID"

    @respx.mock
    async def test_отсутствие_привязки_отличимо_от_прочих_отказов(self) -> None:
        respx.get(f"{BASE}/api/bot/devices").mock(
            return_value=httpx.Response(404, json={"code": "NOT_LINKED"})
        )
        async with make_api() as api:
            result = await api.devices("DISCORD", 7)

        assert result.error == "NOT_LINKED"


class TestНедоступность:
    @respx.mock
    async def test_пятисотка_это_исключение(self) -> None:
        # Показать «код недействителен» при отказе сервера значило бы соврать:
        # ответа по существу не было вовсе.
        respx.get(f"{BASE}/api/bot/account").mock(return_value=httpx.Response(503))
        async with make_api() as api:
            with pytest.raises(ApiUnavailable):
                await api.account("TELEGRAM", 42)

    @respx.mock
    async def test_обрыв_сети_это_исключение(self) -> None:
        respx.get(f"{BASE}/api/bot/account").mock(
            side_effect=httpx.ConnectError("connection refused")
        )
        async with make_api() as api:
            with pytest.raises(ApiUnavailable):
                await api.account("TELEGRAM", 42)


class TestТело:
    @respx.mock
    async def test_пустое_тело_не_роняет_клиента(self) -> None:
        respx.delete(f"{BASE}/api/bot/link").mock(return_value=httpx.Response(200))
        async with make_api() as api:
            result = await api.unlink("TELEGRAM", 42)

        assert result.ok
        assert result.data == {}

    @respx.mock
    async def test_не_json_не_роняет_клиента(self) -> None:
        # Обратный прокси при сбое отдаёт HTML. Это не повод падать.
        respx.get(f"{BASE}/api/bot/history").mock(
            return_value=httpx.Response(200, text="<html>oops</html>")
        )
        async with make_api() as api:
            result = await api.history("TELEGRAM", 42)

        assert result.ok
        assert result.data == {}


class TestКлюч:
    @respx.mock
    async def test_ключ_уходит_в_заголовке(self) -> None:
        route = respx.get(f"{BASE}/api/bot/account").mock(
            return_value=httpx.Response(200, json={})
        )
        async with make_api() as api:
            await api.account("TELEGRAM", 42)

        assert route.calls.last.request.headers["X-Bot-Key"] == "secret"
