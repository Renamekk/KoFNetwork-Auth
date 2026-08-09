"""Получение сообщений KoFAuth ботами.

**Почему это больше не Redis.** Раньше боты подписывались на общий канал
Pub/Sub тем же паролем, что и Core. Отсюда следовали две беды, каждой из
которых хватило бы по отдельности.

Первая — полномочия. Пароль Redis не делится на «читать канал» и «всё
остальное»: процесс на Python получал право читать и переписывать любые
ключи — состояния входа, привязки UUID к сессиям, счётчики ограничения
скорости — и публиковать любые доменные события от имени системы.

Вторая — доставка. Pub/Sub не помнит сообщений. Бот, перезапущенный на две
секунды, терял все запросы подтверждения, пришедшие за это время: игрок в
Minecraft ждал кнопку, которой уже не будет, до самого таймаута.

Здесь бот ходит в ``/api/bot/events`` со своим ключом и получает ровно то,
что ему адресовано. Очередь живёт в базе, курсор — на сервере, поэтому
короткий простой ничего не теряет: сообщения дождутся возвращения.

Доставка — «не меньше одного раза». Бот может упасть между обработкой и
подтверждением, поэтому одно и то же сообщение он обязан выдержать дважды.
Для запроса подтверждения это безопасно: решение принимается атомарно на
стороне сервера, а повторно показанная кнопка ведёт к тому же запросу.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

from .api import ApiUnavailable, KoFAuthApi
from .config import OutboxSettings

LOGGER = logging.getLogger(__name__)

Handler = Callable[["BotMessage"], Awaitable[None]]


@dataclass(frozen=True, slots=True)
class BotMessage:
    """Сообщение из очереди.

    :param id: номер сообщения; он же курсор доставки
    :param kind: что нужно сделать — ``LOGIN_APPROVAL``, ``LOGIN_NOTICE`` и т.д.
    :param recipient_id: кому адресовано в мессенджере
    :param chat_id: куда писать; у Discord совпадает с ``recipient_id``
    :param payload: скалярные поля; секретов здесь нет по построению
    """

    id: int
    kind: str
    recipient_id: int
    chat_id: int
    payload: dict[str, str] = field(default_factory=dict)

    def get(self, name: str, default: str = "") -> str:
        value = self.payload.get(name)
        return default if value is None else str(value)

    @staticmethod
    def parse(raw: Any) -> BotMessage | None:
        """Разбор одного элемента ответа.

        Разбор снисходителен намеренно: неизвестное поле или новый вид
        сообщения не должны останавливать чтение очереди, иначе выпуск новой
        версии Core заглушил бы ботов целиком.
        """
        if not isinstance(raw, dict):
            return None
        try:
            message_id = int(raw.get("id"))
        except (TypeError, ValueError):
            return None

        kind = str(raw.get("kind") or "")
        if not kind:
            return None

        payload = raw.get("payload")
        fields: dict[str, str] = {}
        if isinstance(payload, dict):
            for key, value in payload.items():
                fields[str(key)] = "" if value is None else str(value)

        return BotMessage(
            id=message_id,
            kind=kind,
            recipient_id=_as_int(raw.get("recipientId")),
            chat_id=_as_int(raw.get("chatId")),
            payload=fields,
        )


def _as_int(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


class OutboxListener:
    """Читает очередь сообщений и раздаёт их обработчикам."""

    def __init__(self, api: KoFAuthApi, settings: OutboxSettings) -> None:
        self._api = api
        self._settings = settings
        self._handlers: dict[str, list[Handler]] = {}
        self._task: asyncio.Task[None] | None = None

    def on(self, kind: str, handler: Handler) -> None:
        """Подписывает обработчик на вид сообщения."""
        self._handlers.setdefault(kind, []).append(handler)

    async def start(self) -> None:
        if not self._settings.enabled:
            LOGGER.info("Чтение очереди выключено: подтверждения и уведомления не придут")
            return
        self._task = asyncio.create_task(self._run(), name="kofauth-outbox")

    async def stop(self) -> None:
        if self._task is None:
            return
        self._task.cancel()
        # Отмена собственной задачи — не ошибка, а способ её остановить:
        # исключение здесь ожидаемо и означает, что цикл действительно завершён.
        with contextlib.suppress(asyncio.CancelledError):
            await self._task
        self._task = None

    async def _run(self) -> None:
        """Опрашивает очередь, переживая недоступность API.

        Пауза растёт при отказах и сбрасывается при первом успехе: постоянный
        опрос лежащего сервиса не ускорит его возвращение, а после долгого
        простоя бот не должен ждать минутами.
        """
        delay = self._settings.poll_interval
        while True:
            try:
                delivered = await self.poll_once()
                # Пока сообщения идут потоком, паузу не держим: очередь могла
                # накопиться за время простоя, и разбирать её по одному
                # интервалу значило бы растянуть доставку на минуты.
                delay = self._settings.poll_interval
                if delivered == 0:
                    await asyncio.sleep(delay)
            except asyncio.CancelledError:
                raise
            except ApiUnavailable as exc:
                LOGGER.warning(
                    "Очередь недоступна (%s), повтор через %.0f с", exc, delay
                )
                await asyncio.sleep(delay)
                delay = min(delay * 2, self._settings.max_backoff)
            except Exception:  # noqa: BLE001 — причина не влияет на действие
                LOGGER.exception("Ошибка при чтении очереди")
                await asyncio.sleep(delay)
                delay = min(delay * 2, self._settings.max_backoff)

    async def poll_once(self) -> int:
        """Один цикл: прочитать, раздать, подтвердить.

        :return: сколько сообщений обработано

        Подтверждение отправляется только после того, как все сообщения пачки
        розданы. Упасть между раздачей и подтверждением можно — тогда пачка
        придёт снова, и это лучше, чем потерять её молча.
        """
        result = await self._api.events(self._settings.platform, self._settings.batch_size)
        if not result.ok:
            LOGGER.warning("Очередь ответила отказом: %s", result.error)
            return 0

        raw_messages = result.data.get("messages") or []
        if not raw_messages:
            return 0

        highest = 0
        handled = 0
        for raw in raw_messages:
            message = BotMessage.parse(raw)
            if message is None:
                continue
            highest = max(highest, message.id)
            handled += 1
            await self._dispatch(message)

        if highest:
            await self._api.ack(self._settings.platform, highest)
        return handled

    async def _dispatch(self, message: BotMessage) -> None:
        for handler in self._handlers.get(message.kind, ()):
            try:
                await handler(message)
            except Exception:  # noqa: BLE001 — один обработчик не глушит остальные
                LOGGER.exception("Обработчик сообщения %s упал", message.kind)


# Виды сообщений. Строки совпадают с BotMessage.Kind на стороне Core.
LOGIN_APPROVAL = "LOGIN_APPROVAL"
LOGIN_APPROVAL_RESOLVED = "LOGIN_APPROVAL_RESOLVED"
LOGIN_NOTICE = "LOGIN_NOTICE"
SECURITY_NOTICE = "SECURITY_NOTICE"
