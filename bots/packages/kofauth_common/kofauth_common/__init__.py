"""Общий код ботов KoF Network.

Здесь всё, что одинаково для Telegram и Discord: настройки, клиент REST API,
чтение очереди сообщений и тексты. Платформенные приложения содержат только то,
чем платформы действительно различаются, — способ нарисовать меню и кнопки.
"""

from .api import ApiResult, ApiUnavailable, KoFAuthApi
from .config import BrandSettings, ConfigurationError, OutboxSettings, Settings
from .events import (
    LINK_CODE,
    LOGIN_APPROVAL,
    LOGIN_APPROVAL_RESOLVED,
    LOGIN_NOTICE,
    SECURITY_NOTICE,
    BotMessage,
    OutboxListener,
)

__all__ = [
    "ApiResult",
    "ApiUnavailable",
    "BotMessage",
    "BrandSettings",
    "ConfigurationError",
    "LINK_CODE",
    "LOGIN_APPROVAL",
    "LOGIN_APPROVAL_RESOLVED",
    "LOGIN_NOTICE",
    "OutboxListener",
    "OutboxSettings",
    "KoFAuthApi",
    "SECURITY_NOTICE",
    "Settings",
]
