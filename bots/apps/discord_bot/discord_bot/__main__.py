"""Точка входа Discord-бота."""

from __future__ import annotations

import asyncio
import logging

from kofauth_common import ConfigurationError, KoFAuthApi, OutboxListener, Settings

from .bot import DiscordBot, register_message_handlers

LOGGER = logging.getLogger("kofauth.discord")


async def main() -> int:
    settings = Settings.from_env()
    logging.basicConfig(
        level=settings.log_level,
        format="%(asctime)s %(levelname)-5s %(name)s | %(message)s",
    )

    try:
        settings.discord.validate()
        api = KoFAuthApi(settings.api)
    except ConfigurationError as exc:
        LOGGER.error("Бот не запущен: %s", exc)
        return 1

    bot = DiscordBot(settings, api)
    listener = OutboxListener(api, settings.outbox("DISCORD"))
    register_message_handlers(bot, listener)

    await listener.start()
    try:
        # start() возвращает управление только при остановке клиента;
        # сигналы обрабатывает сам discord.py.
        await bot.start(settings.discord.token)
    finally:
        LOGGER.info("Остановка...")
        await listener.stop()
        await bot.close()
        await api.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
