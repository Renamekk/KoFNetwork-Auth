"""Подписка на события KoFAuth через Redis.

Core рассылает доменные события в общий канал (``RedisEventBridge``). Боты
слушают тот же канал: так уведомление о входе доходит до Telegram, даже если
вход произошёл на другом узле сети.

Событие приходит плоским набором строковых атрибутов — таков формат
``RemoteEvent``. Здесь он превращается в обычный объект, а разбор ошибок
намеренно снисходителен: неизвестное поле или новый тип события не должны
останавливать подписку, иначе выпуск новой версии Core глушил бы ботов.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

import redis.asyncio as redis

from .config import RedisSettings

LOGGER = logging.getLogger(__name__)

Handler = Callable[["Event"], Awaitable[None]]


@dataclass(frozen=True, slots=True)
class Event:
    """Событие KoFAuth.

    :param type: короткое имя класса события, например ``AccountLoginEvent``
    :param account_id: аккаунт, которого событие касается
    :param attributes: остальные поля как строки
    """

    type: str
    account_id: int | None
    attributes: dict[str, str] = field(default_factory=dict)

    def get(self, name: str, default: str = "") -> str:
        return self.attributes.get(name, default)

    def get_bool(self, name: str) -> bool:
        return self.get(name).lower() in {"1", "true", "yes"}

    @staticmethod
    def parse(raw: str) -> "Event | None":
        try:
            payload = json.loads(raw)
        except ValueError:
            LOGGER.warning("Событие не разобрано как JSON: %s", raw[:200])
            return None
        if not isinstance(payload, dict):
            return None

        event_type = str(payload.get("type") or payload.get("eventType") or "")
        if not event_type:
            return None
        # В канал попадает короткое имя класса; полное с пакетом читается хуже
        # и ничего не добавляет.
        event_type = event_type.rsplit(".", 1)[-1]

        account_id = payload.get("accountId")
        try:
            account = int(account_id) if account_id is not None else None
        except (TypeError, ValueError):
            account = None

        attributes: dict[str, str] = {}
        for key, value in payload.items():
            if key in {"type", "eventType", "accountId"}:
                continue
            attributes[key] = "" if value is None else str(value)
        # Часть отправителей кладёт поля во вложенный объект.
        nested = payload.get("attributes")
        if isinstance(nested, dict):
            for key, value in nested.items():
                attributes[key] = "" if value is None else str(value)

        return Event(type=event_type, account_id=account, attributes=attributes)


class EventListener:
    """Читает канал событий и раздаёт их подписчикам."""

    def __init__(self, settings: RedisSettings) -> None:
        self._settings = settings
        self._handlers: dict[str, list[Handler]] = {}
        self._task: asyncio.Task[None] | None = None
        self._client: redis.Redis | None = None

    def on(self, event_type: str, handler: Handler) -> None:
        """Подписывает обработчик на тип события."""
        self._handlers.setdefault(event_type, []).append(handler)

    async def start(self) -> None:
        if not self._settings.enabled:
            LOGGER.info("Подписка на события выключена, уведомления не придут")
            return
        self._task = asyncio.create_task(self._run(), name="kofauth-events")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    async def _run(self) -> None:
        """Читает канал, восстанавливая соединение после разрыва.

        Отказ Redis — не повод останавливать бота: команды продолжают работать
        через REST, теряются только уведомления. Поэтому цикл переподключается
        с паузой, а не завершается.
        """
        delay = 1.0
        while True:
            try:
                await self._consume()
                delay = 1.0
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 — причина не влияет на действие
                LOGGER.warning(
                    "Подписка на события прервана (%s), повтор через %.0f с", exc, delay
                )
                await asyncio.sleep(delay)
                # Потолок нужен, чтобы после долгого простоя Redis бот
                # не ждал часами.
                delay = min(delay * 2, 30.0)

    async def _consume(self) -> None:
        self._client = redis.from_url(self._settings.url, decode_responses=True)
        pubsub = self._client.pubsub(ignore_subscribe_messages=True)
        await pubsub.subscribe(self._settings.channel)
        LOGGER.info("Подписка на канал %s установлена", self._settings.channel)

        async for message in pubsub.listen():
            if message.get("type") != "message":
                continue
            await self._dispatch(str(message.get("data", "")))

    async def _dispatch(self, raw: str) -> None:
        event = Event.parse(raw)
        if event is None:
            return
        for handler in self._handlers.get(event.type, ()):
            try:
                await handler(event)
            except Exception:  # noqa: BLE001 — один обработчик не глушит остальные
                LOGGER.exception("Обработчик события %s упал", event.type)


def payload_to_json(data: dict[str, Any]) -> str:
    """Сериализация для тестов и отладки."""
    return json.dumps(data, ensure_ascii=False)
