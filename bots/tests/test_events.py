"""Чтение очереди сообщений ботами.

Раньше здесь проверялся разбор событий Redis Pub/Sub. Подписки больше нет:
она требовала мастер-пароля хранилища и при этом теряла сообщения при любом
перезапуске бота. Проверяется то, что пришло ей на смену, — долговечная
очередь через ``/api/bot/events`` с курсором на сервере.
"""

from __future__ import annotations

import json

import httpx
import respx
from kofauth_common.api import KoFAuthApi
from kofauth_common.config import ApiSettings, OutboxSettings
from kofauth_common.events import BotMessage, OutboxListener

BASE = "http://webapi:8080"


def make_api() -> KoFAuthApi:
    return KoFAuthApi(ApiSettings(base_url=BASE, bot_key="secret"))


def settings(**overrides) -> OutboxSettings:
    values = {"platform": "TELEGRAM", "poll_interval": 0.01, "batch_size": 10}
    values.update(overrides)
    return OutboxSettings(**values)


class TestРазборСообщения:
    def test_поля_становятся_доступными(self) -> None:
        message = BotMessage.parse({
            "id": 12,
            "kind": "LOGIN_APPROVAL",
            "recipientId": 1001,
            "chatId": 5005,
            "payload": {"approvalId": "abc", "username": "Steve"},
        })

        assert message is not None
        assert message.id == 12
        assert message.kind == "LOGIN_APPROVAL"
        assert message.recipient_id == 1001
        assert message.chat_id == 5005
        assert message.get("approvalId") == "abc"
        assert message.get("нет-такого", "—") == "—"

    def test_числа_приводятся_к_строкам(self) -> None:
        message = BotMessage.parse({
            "id": 1, "kind": "LOGIN_NOTICE", "payload": {"count": 3, "flag": True},
        })

        assert message is not None
        assert message.get("count") == "3"
        assert message.get("flag") == "True"

    def test_сообщение_без_вида_пропускается(self) -> None:
        # Новый вид сообщения не должен останавливать чтение очереди: иначе
        # выпуск новой версии Core заглушил бы ботов целиком.
        assert BotMessage.parse({"id": 1}) is None

    def test_сообщение_без_номера_пропускается(self) -> None:
        assert BotMessage.parse({"kind": "LOGIN_NOTICE"}) is None

    def test_не_объект_пропускается(self) -> None:
        assert BotMessage.parse([1, 2, 3]) is None

    def test_отсутствующий_payload_не_роняет_разбор(self) -> None:
        message = BotMessage.parse({"id": 3, "kind": "LOGIN_NOTICE"})

        assert message is not None
        assert message.get("что-угодно") == ""


class TestЧтениеОчереди:
    @respx.mock
    async def test_сообщения_раздаются_и_подтверждаются(self) -> None:
        respx.get(f"{BASE}/api/bot/events").mock(
            return_value=httpx.Response(200, json={"cursor": 0, "messages": [
                {"id": 1, "kind": "LOGIN_APPROVAL", "recipientId": 7,
                 "payload": {"approvalId": "a1"}},
                {"id": 2, "kind": "LOGIN_NOTICE", "recipientId": 7, "payload": {}},
            ]})
        )
        ack = respx.post(f"{BASE}/api/bot/events/ack").mock(
            return_value=httpx.Response(200, json={"cursor": 2})
        )

        seen: list[str] = []

        async with make_api() as api:
            listener = OutboxListener(api, settings())
            listener.on("LOGIN_APPROVAL", lambda m: _record(seen, m))
            listener.on("LOGIN_NOTICE", lambda m: _record(seen, m))

            handled = await listener.poll_once()

        assert handled == 2
        assert seen == ["LOGIN_APPROVAL", "LOGIN_NOTICE"]
        # Курсор двигается на самый большой номер пачки — и только после того,
        # как все сообщения розданы.
        assert ack.called
        acknowledged = json.loads(ack.calls.last.request.content)
        assert acknowledged == {"platform": "TELEGRAM", "cursor": 2}

    @respx.mock
    async def test_пустая_очередь_ничего_не_подтверждает(self) -> None:
        respx.get(f"{BASE}/api/bot/events").mock(
            return_value=httpx.Response(200, json={"cursor": 5, "messages": []})
        )
        ack = respx.post(f"{BASE}/api/bot/events/ack")

        async with make_api() as api:
            handled = await OutboxListener(api, settings()).poll_once()

        assert handled == 0
        assert not ack.called

    @respx.mock
    async def test_падение_обработчика_не_глушит_остальных(self) -> None:
        respx.get(f"{BASE}/api/bot/events").mock(
            return_value=httpx.Response(200, json={"cursor": 0, "messages": [
                {"id": 1, "kind": "LOGIN_NOTICE", "recipientId": 7, "payload": {}},
            ]})
        )
        respx.post(f"{BASE}/api/bot/events/ack").mock(
            return_value=httpx.Response(200, json={"cursor": 1})
        )

        seen: list[str] = []

        async def падающий(message: BotMessage) -> None:
            raise RuntimeError("бот сломался")

        async with make_api() as api:
            listener = OutboxListener(api, settings())
            listener.on("LOGIN_NOTICE", падающий)
            listener.on("LOGIN_NOTICE", lambda m: _record(seen, m))

            handled = await listener.poll_once()

        assert handled == 1
        assert seen == ["LOGIN_NOTICE"]

    @respx.mock
    async def test_повторная_доставка_безопасна(self) -> None:
        # Бот мог упасть между обработкой и подтверждением, поэтому пачка
        # приходит снова. Обработчик обязан выдержать это.
        respx.get(f"{BASE}/api/bot/events").mock(
            return_value=httpx.Response(200, json={"cursor": 0, "messages": [
                {"id": 1, "kind": "LOGIN_APPROVAL", "recipientId": 7,
                 "payload": {"approvalId": "a1"}},
            ]})
        )
        respx.post(f"{BASE}/api/bot/events/ack").mock(
            return_value=httpx.Response(200, json={"cursor": 1})
        )

        seen: list[str] = []

        async with make_api() as api:
            listener = OutboxListener(api, settings())
            listener.on("LOGIN_APPROVAL", lambda m: _record(seen, m))

            await listener.poll_once()
            await listener.poll_once()

        assert seen == ["LOGIN_APPROVAL", "LOGIN_APPROVAL"]

    @respx.mock
    async def test_отказ_по_существу_не_двигает_курсор(self) -> None:
        respx.get(f"{BASE}/api/bot/events").mock(
            return_value=httpx.Response(401, json={"code": "BOT_UNAUTHORIZED"})
        )
        ack = respx.post(f"{BASE}/api/bot/events/ack")

        async with make_api() as api:
            handled = await OutboxListener(api, settings()).poll_once()

        assert handled == 0
        assert not ack.called


async def _record(sink: list[str], message: BotMessage) -> None:
    sink.append(message.kind)
