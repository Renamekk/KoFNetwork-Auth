"""Настройки ботов.

Читаются из окружения, а не из YAML KoFAuth. Причина в том, что боты живут в
отдельных контейнерах и знают о системе ровно две вещи: адрес REST API и ключ
доступа к нему. Монтировать в них весь каталог конфигурации значило бы отдать
контейнеру бота секреты базы и JWT, которые ему не нужны.

Имена переменных совпадают с теми, что уже использует docker-compose KoFAuth,
поэтому отдельного файла для ботов не появляется.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def _env_int(name: str, default: int) -> int:
    raw = _env(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_bool(name: str, default: bool) -> bool:
    raw = _env(name).lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on", "да"}


def _env_list(name: str) -> list[str]:
    raw = _env(name)
    return [part.strip() for part in raw.split(",") if part.strip()]


class ConfigurationError(RuntimeError):
    """Бот не может работать с такой конфигурацией."""


@dataclass(frozen=True, slots=True)
class ApiSettings:
    """Доступ к REST API KoFAuth."""

    base_url: str
    bot_key: str
    timeout_seconds: float = 10.0

    def validate(self) -> None:
        if not self.base_url:
            raise ConfigurationError(
                "Не задан KOFAUTH_API_URL — боту некуда обращаться"
            )
        if not self.bot_key:
            # Пустой ключ означал бы анонимные запросы к /api/bot, а он их
            # отвергает. Падать здесь честнее, чем на каждой команде игрока.
            raise ConfigurationError(
                "Не задан KOFAUTH_SECURITY_BOT_API_KEY — /api/bot отвергнет запросы"
            )


@dataclass(frozen=True, slots=True)
class RedisSettings:
    """Подписка на события KoFAuth.

    Боты слушают тот же канал, в который пишет ``RedisEventBridge``. Свои
    события они не публикуют: всё, что они делают, проходит через REST.
    """

    url: str
    channel: str = "kofauth:events"
    enabled: bool = True


@dataclass(frozen=True, slots=True)
class TelegramSettings:
    token: str
    username: str = ""
    admin_chat_ids: list[str] = field(default_factory=list)

    def validate(self) -> None:
        if not self.token:
            raise ConfigurationError("Не задан KOFAUTH_TELEGRAM_BOT_TOKEN")


@dataclass(frozen=True, slots=True)
class DiscordSettings:
    token: str
    guild_id: str = ""
    admin_channel_id: str = ""

    def validate(self) -> None:
        if not self.token:
            raise ConfigurationError("Не задан KOFAUTH_DISCORD_BOT_TOKEN")

    @property
    def guild_id_int(self) -> int | None:
        """Идентификатор сервера для мгновенной регистрации команд.

        Пусто — команды регистрируются глобально, и Discord распространяет их
        до часа. На разработке это неудобно настолько, что проще указать сервер.
        """
        return int(self.guild_id) if self.guild_id.isdigit() else None


@dataclass(frozen=True, slots=True)
class Settings:
    """Всё, что нужно любому из ботов."""

    api: ApiSettings
    redis: RedisSettings
    telegram: TelegramSettings
    discord: DiscordSettings
    panel_url: str
    log_level: str

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            api=ApiSettings(
                base_url=_env("KOFAUTH_API_URL", "http://webapi:8080").rstrip("/"),
                bot_key=_env("KOFAUTH_SECURITY_BOT_API_KEY"),
                timeout_seconds=float(_env_int("KOFAUTH_API_TIMEOUT_SECONDS", 10)),
            ),
            redis=RedisSettings(
                url=_env("KOFAUTH_REDIS_URL", "redis://redis:6379/0"),
                channel=_env("KOFAUTH_EVENT_CHANNEL", "kofauth:events"),
                enabled=_env_bool("KOFAUTH_EVENTS_ENABLED", True),
            ),
            telegram=TelegramSettings(
                token=_env("KOFAUTH_TELEGRAM_BOT_TOKEN"),
                username=_env("KOFAUTH_TELEGRAM_BOT_USERNAME"),
                admin_chat_ids=_env_list("KOFAUTH_TELEGRAM_ADMIN_CHAT_IDS"),
            ),
            discord=DiscordSettings(
                token=_env("KOFAUTH_DISCORD_BOT_TOKEN"),
                guild_id=_env("KOFAUTH_DISCORD_GUILD_ID"),
                admin_channel_id=_env("KOFAUTH_DISCORD_ADMIN_CHANNEL_ID"),
            ),
            panel_url=_env("KOFAUTH_PANEL_URL", "http://127.0.0.1:8080"),
            log_level=_env("KOFAUTH_LOG_LEVEL", "INFO").upper(),
        )
