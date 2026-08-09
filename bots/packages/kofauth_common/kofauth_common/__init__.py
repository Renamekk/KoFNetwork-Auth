"""Общий код ботов KoF Network.

Здесь всё, что одинаково для Telegram и Discord: настройки, клиент REST API,
подписка на события и тексты. Платформенные приложения содержат только то,
чем платформы действительно различаются, — способ нарисовать меню.
"""

from .api import ApiResult, ApiUnavailable, KoFAuthApi
from .config import ConfigurationError, Settings
from .events import Event, EventListener

__all__ = [
    "ApiResult",
    "ApiUnavailable",
    "ConfigurationError",
    "Event",
    "EventListener",
    "KoFAuthApi",
    "Settings",
]
