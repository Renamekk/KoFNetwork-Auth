"""Точка входа Telegram-бота."""

from __future__ import annotations

import asyncio
import logging
import signal

from kofauth_common import ConfigurationError, EventListener, KoFAuthApi, Settings

from .bot import TelegramBot, register_event_handlers

LOGGER = logging.getLogger("kofauth.telegram")


async def main() -> int:
    settings = Settings.from_env()
    logging.basicConfig(
        level=settings.log_level,
        format="%(asctime)s %(levelname)-5s %(name)s | %(message)s",
    )

    try:
        settings.telegram.validate()
        api = KoFAuthApi(settings.api)
    except ConfigurationError as exc:
        # Бот без токена или ключа не сделает ничего полезного. Падение на
        # старте заметно; молчаливая работа «вхолостую» — нет.
        LOGGER.error("Бот не запущен: %s", exc)
        return 1

    bot = TelegramBot(settings, api)
    listener = EventListener(settings.redis)
    register_event_handlers(bot, listener)

    await listener.start()
    LOGGER.info("Telegram-бот запущен")

    stop = asyncio.Event()
    _install_signal_handlers(stop)

    polling = asyncio.create_task(bot.run(), name="telegram-polling")
    await asyncio.wait({polling, asyncio.create_task(stop.wait())},
                       return_when=asyncio.FIRST_COMPLETED)

    LOGGER.info("Остановка...")
    polling.cancel()
    await listener.stop()
    await bot.close()
    await api.close()
    return 0


def _install_signal_handlers(stop: asyncio.Event) -> None:
    """Останавливается по сигналу из Docker, а не по обрыву соединений."""
    loop = asyncio.get_running_loop()
    for name in ("SIGINT", "SIGTERM"):
        sig = getattr(signal, name, None)
        if sig is None:
            continue
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:
            # Windows не поддерживает add_signal_handler для SIGTERM.
            signal.signal(sig, lambda *_: stop.set())


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
