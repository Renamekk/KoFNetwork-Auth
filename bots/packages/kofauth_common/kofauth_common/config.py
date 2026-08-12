"""Настройки ботов.

Читаются из окружения, а не из YAML KoFAuth. Причина в том, что боты живут в
отдельных контейнерах и знают о системе ровно две вещи: адрес REST API и ключ
доступа к нему. Монтировать в них весь каталог конфигурации значило бы отдать
контейнеру бота секреты базы и JWT, которые ему не нужны.

По той же причине здесь больше нет настроек Redis. Прежде бот получал
мастер-пароль хранилища ради чтения одного канала — вместе с правом
переписывать состояния входа и публиковать любые события от имени системы.
Сообщения приходят через ``/api/bot/events``, и ключа ``/api/bot`` достаточно.

Имена переменных совпадают с теми, что уже использует docker-compose KoFAuth,
поэтому отдельного файла для ботов не появляется.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

from .cards.settings import CardSettings
from .glyphs import EmojiSet


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
class OutboxSettings:
    """Чтение очереди сообщений из ``/api/bot/events``.

    Раньше на этом месте были настройки Redis: адрес и канал. Боты получали
    мастер-пароль хранилища — то есть право переписывать состояния входа,
    привязки UUID и счётчики лимитов, а заодно публиковать любые доменные
    события от имени системы. При этом доставка оставалась ненадёжной:
    Pub/Sub не помнит сообщений, и перезапуск бота на пару секунд терял все
    запросы подтверждения, пришедшие за это время.

    Теперь боту нужен только ключ ``/api/bot``, а очередь живёт в базе:
    короткий простой ничего не теряет.

    :param platform: чья это очередь — ``TELEGRAM`` или ``DISCORD``
    :param poll_interval: пауза между опросами, когда очередь пуста
    :param max_backoff: потолок паузы при недоступном API
    """

    platform: str
    enabled: bool = True
    poll_interval: float = 2.0
    max_backoff: float = 30.0
    batch_size: int = 100


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
    account_channel_id: str = ""

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

    @property
    def account_channel_id_int(self) -> int | None:
        """Канал привязки: туда игрок приходит по ссылке из игры.

        Пусто — публиковать некуда, и сообщения этого рода просто не
        отправляются. Отсутствие канала не ошибка: привязка работает и без него.
        """
        return int(self.account_channel_id) if self.account_channel_id.isdigit() else None


@dataclass(frozen=True, slots=True)
class BrandSettings:
    """Оформление: то, что делает бота узнаваемым, а не работающим.

    Вынесено отдельно, потому что ничего из этого не влияет на поведение: без
    баннера и ссылки на донат бот работает ровно так же, просто выглядит голым.
    Поэтому и проверок здесь нет — пустое значение означает «не показывать».

    :param banner_url: изображение шапки; должно быть доступно по HTTP как
        Discord, так и Telegram — оба скачивают его своими серверами
    :param banner_file: локальный файл на случай, когда выкладывать баннер
        наружу незачем. Telegram загружает его один раз и дальше пользуется
        выданным ``file_id``; Discord умеет только ссылку
    :param donate_url: страница поддержки сервера, показывается в профиле
    :param emoji_discord: фирменные emoji на кнопках Discord, ``ключ=<:имя:id>``
        через запятую. Идентификатор выдаёт сама платформа — выдумать его
        нельзя, поэтому по умолчанию тут пусто
    :param emoji_telegram: фирменные emoji в тексте Telegram, ``ключ=id``
        документа custom emoji
    """

    banner_url: str = ""
    banner_file: str = ""
    donate_url: str = ""
    site_url: str = ""
    emoji_discord: str = ""
    emoji_telegram: str = ""

    def emoji(self) -> EmojiSet:
        """Разобранный набор значков.

        Разбор ленивый и по требованию: боту он нужен один раз при сборке, а
        держать в настройках готовый объект значило бы разбирать окружение
        раньше, чем кто-нибудь сообщит об ошибке в нём.
        """
        return EmojiSet(self.emoji_discord, self.emoji_telegram)


@dataclass(frozen=True, slots=True)
class Settings:
    """Всё, что нужно любому из ботов."""

    api: ApiSettings
    telegram: TelegramSettings
    discord: DiscordSettings
    brand: BrandSettings
    cards: CardSettings
    panel_url: str
    log_level: str

    def outbox(self, platform: str) -> OutboxSettings:
        """Настройки очереди для конкретной платформы."""
        return OutboxSettings(
            platform=platform,
            enabled=_env_bool("KOFAUTH_EVENTS_ENABLED", True),
            poll_interval=float(_env_int("KOFAUTH_EVENTS_POLL_SECONDS", 2)),
            max_backoff=float(_env_int("KOFAUTH_EVENTS_MAX_BACKOFF_SECONDS", 30)),
            batch_size=_env_int("KOFAUTH_EVENTS_BATCH_SIZE", 100),
        )

    @staticmethod
    def from_env() -> Settings:
        return Settings(
            api=ApiSettings(
                base_url=_env("KOFAUTH_API_URL", "http://webapi:8080").rstrip("/"),
                bot_key=_env("KOFAUTH_SECURITY_BOT_API_KEY"),
                timeout_seconds=float(_env_int("KOFAUTH_API_TIMEOUT_SECONDS", 10)),
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
                account_channel_id=_env("KOFAUTH_DISCORD_ACCOUNT_CHANNEL_ID"),
            ),
            brand=BrandSettings(
                banner_url=_env("KOFAUTH_BANNER_URL"),
                banner_file=_env("KOFAUTH_BANNER_FILE"),
                donate_url=_env("KOFAUTH_DONATE_URL"),
                site_url=_env("KOFAUTH_SITE_URL"),
                emoji_discord=_env("KOFAUTH_EMOJI_DISCORD"),
                emoji_telegram=_env("KOFAUTH_EMOJI_TELEGRAM"),
            ),
            cards=CardSettings(
                enabled=_env_bool("KOFAUTH_CARDS_ENABLED", True),
                directory=_env("KOFAUTH_CARDS_DIR"),
                quality=_env_int("KOFAUTH_CARDS_QUALITY", 84),
                workers=_env_int("KOFAUTH_CARDS_WORKERS", 2),
                cache_entries=_env_int("KOFAUTH_CARDS_CACHE_ENTRIES", 48),
                cache_bytes=_env_int("KOFAUTH_CARDS_CACHE_MB", 24) * 1024 * 1024,
                cache_ttl=float(_env_int("KOFAUTH_CARDS_CACHE_TTL_SECONDS", 600)),
            ),
            panel_url=_env("KOFAUTH_PANEL_URL", "http://127.0.0.1:8080"),
            log_level=_env("KOFAUTH_LOG_LEVEL", "INFO").upper(),
        )
