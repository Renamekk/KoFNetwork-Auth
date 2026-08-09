"""Клиент REST API KoFAuth для ботов.

Единственный способ, которым боты меняют состояние системы. Прямого доступа
к MySQL и Redis у них нет намеренно: логика безопасности — сроки токенов,
порядок проверок, ограничение частоты — живёт в Core в одном экземпляре.
Второй её экземпляр на Python неизбежно разошёлся бы с первым.

Ошибки транспорта и ошибки предметной области здесь разделены. Отказ сети —
исключение: продолжать нечем. Отказ по существу («запрос устарел», «уже
привязано») — обычный результат, который бот показывает человеку, поэтому он
возвращается значением, а не исключением.

Через этот же клиент приходят сообщения из очереди: подписки на Redis у ботов
больше нет. Она требовала мастер-пароля хранилища и при этом теряла сообщения
при любом перезапуске бота.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

import httpx

from .config import ApiSettings

LOGGER = logging.getLogger(__name__)


class ApiUnavailable(RuntimeError):
    """API недоступен: сеть, таймаут или 5xx."""


@dataclass(frozen=True, slots=True)
class ApiResult:
    """Ответ API.

    :param ok: удалось ли выполнить операцию по существу
    :param data: полезная нагрузка успешного ответа
    :param code: машинный код ошибки, если операция не удалась
    :param message: пояснение от сервера
    """

    ok: bool
    data: dict[str, Any]
    code: str = ""
    message: str = ""

    @property
    def error(self) -> str:
        """Код ошибки либо пустая строка."""
        return "" if self.ok else (self.code or "ERROR")


class KoFAuthApi:
    """Асинхронный клиент ``/api/bot``."""

    def __init__(self, settings: ApiSettings) -> None:
        settings.validate()
        self._settings = settings
        self._client = httpx.AsyncClient(
            base_url=settings.base_url,
            timeout=settings.timeout_seconds,
            headers={"X-Bot-Key": settings.bot_key},
        )

    async def close(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> KoFAuthApi:
        return self

    async def __aexit__(self, *exc_info: object) -> None:
        await self.close()

    # ------------------------------------------------------------------ привязка

    async def link(
        self, platform: str, code: str, external_id: int, chat_id: int = 0
    ) -> ApiResult:
        """Привязывает мессенджер по коду, выданному в игре."""
        return await self._request(
            "POST",
            "/api/bot/link",
            json={
                "platform": platform,
                "code": code,
                "externalId": external_id,
                "chatId": chat_id,
            },
        )

    async def unlink(self, platform: str, external_id: int) -> ApiResult:
        return await self._request(
            "DELETE",
            "/api/bot/link",
            json={"platform": platform, "externalId": external_id},
        )

    # ------------------------------------------------------------------ сведения

    async def account(self, platform: str, external_id: int) -> ApiResult:
        return await self._request(
            "GET",
            "/api/bot/account",
            params={"platform": platform, "externalId": external_id},
        )

    async def devices(
        self, platform: str, external_id: int, limit: int = 10
    ) -> ApiResult:
        return await self._request(
            "GET",
            "/api/bot/devices",
            params={"platform": platform, "externalId": external_id, "limit": limit},
        )

    async def history(
        self, platform: str, external_id: int, limit: int = 10
    ) -> ApiResult:
        return await self._request(
            "GET",
            "/api/bot/history",
            params={"platform": platform, "externalId": external_id, "limit": limit},
        )

    async def sessions(self, platform: str, external_id: int) -> ApiResult:
        return await self._request(
            "GET",
            "/api/bot/sessions",
            params={"platform": platform, "externalId": external_id},
        )

    # ------------------------------------------------------------------ действия

    async def approve(
        self, platform: str, external_id: int, approval_id: str, approved: bool
    ) -> ApiResult:
        """Передаёт нажатие кнопки на сервер.

        Идентификатор запроса сам по себе ничего не открывает: сервер сверяет,
        что нажал тот, кому кнопка адресована, и применяет решение атомарно.
        Поэтому вместе с идентификатором обязательно едет ``external_id``
        нажавшего — его подставляет бот из данных мессенджера, а не человек.

        Повторное нажатие вернёт уже записанный исход, а не создаст вторую
        сессию: ``result`` расскажет, что именно произошло.
        """
        return await self._request(
            "POST",
            "/api/bot/approval",
            json={
                "platform": platform,
                "externalId": external_id,
                "approvalId": approval_id,
                "approved": approved,
            },
        )

    # ------------------------------------------------------------------ очередь

    async def events(self, platform: str, limit: int = 100) -> ApiResult:
        """Читает очередь сообщений, адресованных этой платформе."""
        return await self._request(
            "GET",
            "/api/bot/events",
            params={"platform": platform, "limit": limit},
        )

    async def ack(self, platform: str, cursor: int) -> ApiResult:
        """Подтверждает обработку сообщений до указанного номера."""
        return await self._request(
            "POST",
            "/api/bot/events/ack",
            json={"platform": platform, "cursor": cursor},
        )

    async def set_login_approval(
        self, platform: str, external_id: int, enabled: bool
    ) -> ApiResult:
        return await self._request(
            "POST",
            "/api/bot/security",
            json={
                "platform": platform,
                "externalId": external_id,
                "loginApproval": enabled,
            },
        )

    async def logout_all(self, platform: str, external_id: int) -> ApiResult:
        return await self._request(
            "POST",
            "/api/bot/logout-all",
            json={"platform": platform, "externalId": external_id},
        )

    # ------------------------------------------------------------------ транспорт

    async def _request(
        self,
        method: str,
        path: str,
        *,
        json: dict[str, Any] | None = None,
        params: dict[str, Any] | None = None,
    ) -> ApiResult:
        try:
            response = await self._client.request(
                method, path, json=json, params=params
            )
        except httpx.HTTPError as exc:
            # Сеть отвалилась — это не «операция не удалась», а «ответа нет».
            # Показывать человеку «код недействителен» было бы враньём.
            LOGGER.warning("Запрос %s %s не дошёл: %s", method, path, exc)
            raise ApiUnavailable(str(exc)) from exc

        if response.status_code >= 500:
            LOGGER.error(
                "API ответил %s на %s %s", response.status_code, method, path
            )
            raise ApiUnavailable(f"HTTP {response.status_code}")

        payload = self._payload(response)

        if response.is_success:
            return ApiResult(ok=True, data=payload)

        return ApiResult(
            ok=False,
            data=payload,
            code=str(payload.get("code") or payload.get("error") or "ERROR"),
            message=str(payload.get("message") or ""),
        )

    @staticmethod
    def _payload(response: httpx.Response) -> dict[str, Any]:
        """Тело ответа как словарь.

        Пустой ответ и не-JSON не должны ронять бота: у части эндпоинтов тела
        нет вовсе, а обратный прокси при сбое возвращает HTML.
        """
        if not response.content:
            return {}
        try:
            body = response.json()
        except ValueError:
            LOGGER.warning("Ответ не в JSON: %s", response.text[:200])
            return {}
        return body if isinstance(body, dict) else {"value": body}
