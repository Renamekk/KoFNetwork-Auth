"""Общий код ботов KoF Network.

Здесь всё, что одинаково для Telegram и Discord: настройки, клиент REST API,
чтение очереди сообщений и тексты. Платформенные приложения содержат только то,
чем платформы действительно различаются, — способ нарисовать меню и кнопки.
"""

from .api import ApiResult, ApiUnavailable, KoFAuthApi
from .cards import Card, CardService, CardSettings
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
from .glyphs import EmojiSet, strip_custom_emoji

__all__ = [
    "ApiResult",
    "ApiUnavailable",
    "BotMessage",
    "BrandSettings",
    "Card",
    "CardService",
    "CardSettings",
    "ConfigurationError",
    "EmojiSet",
    "LINK_CODE",
    "LOGIN_APPROVAL",
    "LOGIN_APPROVAL_RESOLVED",
    "LOGIN_NOTICE",
    "OutboxListener",
    "OutboxSettings",
    "KoFAuthApi",
    "SECURITY_NOTICE",
    "Settings",
    "strip_custom_emoji",
]
