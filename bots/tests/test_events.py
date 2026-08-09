"""Разбор событий KoFAuth."""

from __future__ import annotations

import json

from kofauth_common.events import Event


class TestРазбор:
    def test_плоские_поля_становятся_атрибутами(self) -> None:
        raw = json.dumps({
            "type": "AccountLoginEvent",
            "accountId": 7,
            "username": "Steve",
            "ipMasked": "192.168.*.*",
        })

        event = Event.parse(raw)

        assert event is not None
        assert event.type == "AccountLoginEvent"
        assert event.account_id == 7
        assert event.get("username") == "Steve"

    def test_вложенные_атрибуты_тоже_читаются(self) -> None:
        # RemoteEvent кладёт поля во вложенный объект; часть отправителей —
        # рядом с типом. Бот обязан понимать оба варианта.
        raw = json.dumps({
            "type": "AccountLoginEvent",
            "accountId": 7,
            "attributes": {"username": "Steve"},
        })

        event = Event.parse(raw)

        assert event is not None
        assert event.get("username") == "Steve"

    def test_полное_имя_класса_сокращается(self) -> None:
        raw = json.dumps({
            "type": "net.kofnetwork.auth.api.event.events.AccountLoginEvent",
            "accountId": 1,
        })

        event = Event.parse(raw)

        assert event is not None
        assert event.type == "AccountLoginEvent"

    def test_событие_без_аккаунта_допустимо(self) -> None:
        # Часть событий касается всей сети, а не конкретной строки в users.
        raw = json.dumps({"type": "DataExportedEvent"})

        event = Event.parse(raw)

        assert event is not None
        assert event.account_id is None

    def test_числа_и_булевы_приводятся_к_строкам(self) -> None:
        raw = json.dumps({"type": "X", "accountId": 1, "newDevice": True, "count": 3})

        event = Event.parse(raw)

        assert event is not None
        assert event.get("count") == "3"
        assert event.get_bool("newDevice") is True


class TestУстойчивость:
    def test_не_json_не_роняет_подписку(self) -> None:
        # Мусор в канале не должен останавливать чтение: иначе один кривой
        # publish глушит уведомления всей сети.
        assert Event.parse("не json") is None

    def test_json_без_типа_пропускается(self) -> None:
        assert Event.parse(json.dumps({"accountId": 1})) is None

    def test_нечисловой_аккаунт_не_роняет_разбор(self) -> None:
        raw = json.dumps({"type": "X", "accountId": "не число"})

        event = Event.parse(raw)

        assert event is not None
        assert event.account_id is None

    def test_массив_вместо_объекта_пропускается(self) -> None:
        assert Event.parse(json.dumps([1, 2, 3])) is None
