"""Клиент REST API KoFAuth для ботов.

Единственный способ, которым боты меняют состояние системы. Прямого доступа
к MySQL и Redis у них нет намеренно: логика безопасности — сроки токенов,
порядок проверок, ограничение частоты — живёт в Core в одном экземпляре.
Второй её экземпляр на Python неизбежно разошёлся бы с первым.

Ошибки транспорта и ошибки предметной области здесь разделены. Отказ сети —
исключение: продолжать нечем. Отказ по существу («код истёк», «уже привязано»)
— обычный результат, который бот показывает человеку, поэтому он возвращается
значением, а не исключением.
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

    async def __aenter__(self) -> "KoFAuthApi":
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

    async def approve(self, token: str, approved: bool) -> ApiResult:
        """Подтверждает или отклоняет вход.

        Токен одноразовый: повторное нажатие кнопки вернёт неуспех, а не создаст
        вторую сессию.
        """
        return await self._request(
            "POST", "/api/bot/approval", json={"token": token, "approved": approved}
        )

    async def send_code(self, platform: str, external_id: int) -> ApiResult:
        """Выдаёт код подтверждения входа для ручного ввода в игре."""
        return await self._request(
            "POST",
            "/api/bot/sendcode",
            json={"platform": platform, "externalId": external_id},
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
