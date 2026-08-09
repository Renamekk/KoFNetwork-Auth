"""Discord-бот KoF Network.

Slash-команды плюс меню на кнопках. Ответы эфемерные: профиль, устройства и
история — личные данные, и показывать их всему каналу нельзя, даже если команду
там и набрали.

Как и Telegram-бот, тонкий: решения принимает Core через ``/api/bot``.
"""

from __future__ import annotations

import logging
from typing import Any

import discord
from discord import app_commands
from kofauth_common import (
    ApiUnavailable,
    BotMessage,
    KoFAuthApi,
    OutboxListener,
    Settings,
    texts,
)
from kofauth_common import events as event_kinds

from .views import PLATFORM, ApprovalView, MenuView, embed

LOGGER = logging.getLogger(__name__)


class DiscordBot(discord.Client):
    """Клиент Discord с зарегистрированными командами."""

    def __init__(self, settings: Settings, api: KoFAuthApi) -> None:
        # Намерения минимальны: боту не нужно ни содержимое сообщений,
        # ни список участников — он работает только через взаимодействия.
        super().__init__(intents=discord.Intents.none())
        self._settings = settings
        self._api = api
        self.tree = app_commands.CommandTree(self)
        self._register()

    async def setup_hook(self) -> None:
        """Регистрирует команды.

        На конкретном сервере они появляются мгновенно, глобально — до часа.
        Поэтому при заданном guild-id команды копируются на сервер: иначе
        отладка превращается в ожидание.
        """
        guild_id = self._settings.discord.guild_id_int
        if guild_id is not None:
            guild = discord.Object(id=guild_id)
            self.tree.copy_global_to(guild=guild)
            await self.tree.sync(guild=guild)
            LOGGER.info("Команды зарегистрированы на сервере %s", guild_id)
        else:
            await self.tree.sync()
            LOGGER.info("Команды зарегистрированы глобально (обновление до часа)")

    async def on_ready(self) -> None:
        LOGGER.info("Discord-бот вошёл как %s", self.user)

    # ------------------------------------------------------------------ команды

    def _register(self) -> None:
        tree = self.tree
        api = self._api
        panel_url = self._settings.panel_url

        @tree.command(name="menu", description="Меню KoF Network")
        async def menu_command(interaction: discord.Interaction) -> None:
            result = await _safe(interaction, api.account(PLATFORM, interaction.user.id))
            linked = result is not None and result.ok
            lines = (
                [f"Аккаунт: **{result.data.get('username', '—')}**", "", "Выберите раздел."]
                if linked
                else ["Аккаунт не привязан.", "",
                      "Возьмите код в игре командой `/discord` и пришлите `/link КОД`."]
            )
            await interaction.response.send_message(
                embed=embed("KoF Network", lines),
                view=MenuView(api, interaction.user.id, linked, panel_url),
                ephemeral=True,
            )

        @tree.command(name="link", description="Привязать аккаунт по коду из игры")
        @app_commands.describe(code="Код, полученный в игре командой /discord")
        async def link_command(interaction: discord.Interaction, code: str) -> None:
            result = await _safe(interaction, api.link(PLATFORM, code, interaction.user.id))
            if result is None:
                return
            if not result.ok:
                await interaction.response.send_message(
                    texts.describe_error(result.error), ephemeral=True
                )
                return
            await interaction.response.send_message(
                embed=embed("Аккаунт привязан",
                            [f"Ник: **{result.data.get('username', '')}**"]),
                view=MenuView(api, interaction.user.id, True, panel_url),
                ephemeral=True,
            )

        @tree.command(name="profile", description="Сведения об аккаунте")
        async def profile_command(interaction: discord.Interaction) -> None:
            await _screen(interaction, api, "Профиль",
                          api.account(PLATFORM, interaction.user.id),
                          lambda data: texts.profile_lines(data))

        @tree.command(name="security", description="Защита аккаунта")
        async def security_command(interaction: discord.Interaction) -> None:
            await _screen(interaction, api, "Защита аккаунта",
                          api.account(PLATFORM, interaction.user.id),
                          lambda data: texts.security_lines(data))

        @tree.command(name="devices", description="Устройства")
        async def devices_command(interaction: discord.Interaction) -> None:
            await _screen(interaction, api, "Устройства",
                          api.devices(PLATFORM, interaction.user.id),
                          lambda data: texts.device_lines(data.get("devices", [])))

        @tree.command(name="history", description="История входов")
        async def history_command(interaction: discord.Interaction) -> None:
            await _screen(interaction, api, "Последние входы",
                          api.history(PLATFORM, interaction.user.id),
                          lambda data: texts.history_lines(data.get("history", [])))

        @tree.command(name="sessions", description="Активные сессии")
        async def sessions_command(interaction: discord.Interaction) -> None:
            await _screen(interaction, api, "Активные сессии",
                          api.sessions(PLATFORM, interaction.user.id),
                          lambda data: texts.session_lines(data.get("sessions", [])))

        @tree.command(name="unlink", description="Отвязать Discord")
        async def unlink_command(interaction: discord.Interaction) -> None:
            result = await _safe(interaction, api.unlink(PLATFORM, interaction.user.id))
            if result is None:
                return
            await interaction.response.send_message(
                "Аккаунт отвязан." if result.ok else texts.describe_error(result.error),
                ephemeral=True,
            )

        @tree.command(name="login", description="Как подтвердить вход")
        async def login_command(interaction: discord.Interaction) -> None:
            await interaction.response.send_message(
                "Подтверждение входа приходит в личные сообщения само, "
                "когда вы заходите в игру.\n\n"
                "Вводить в игре ничего не нужно. Если сообщение не пришло — "
                "повторите вход в Minecraft, запрос придёт заново.",
                ephemeral=True,
            )

    # ------------------------------------------------------------------ уведомления

    async def request_approval(self, message: BotMessage) -> None:
        """Присылает запрос подтверждения входа в личные сообщения."""
        recipient = message.recipient_id
        user = await self._resolve(recipient)
        if user is None:
            return
        lines = texts.approval_request_lines(
            message.get("username", "—"),
            message.get("ip", "—"),
            texts.format_location(message.get("country"), message.get("city")),
        )
        try:
            await user.send(
                embed=embed("🔐 " + lines[0], lines[1:], colour=0xFFB020),
                view=ApprovalView(self._api, message.get("approvalId"), recipient),
            )
        except discord.HTTPException as exc:
            # Личные сообщения могут быть закрыты — это выбор человека, а не сбой
            # системы. Запасного пути с кодом больше нет и быть не должно: код
            # можно было получить без всякой попытки входа. Игрок повторяет вход,
            # и запрос приходит заново.
            LOGGER.info("Не удалось написать %s: %s", recipient, exc)

    async def notify(self, discord_id: int, title: str, lines: list[str]) -> None:
        user = await self._resolve(discord_id)
        if user is None:
            return
        try:
            await user.send(embed=embed(title, lines))
        except discord.HTTPException as exc:
            LOGGER.info("Уведомление для %s не доставлено: %s", discord_id, exc)

    async def _resolve(self, discord_id: int) -> discord.User | None:
        try:
            return self.get_user(discord_id) or await self.fetch_user(discord_id)
        except discord.HTTPException:
            LOGGER.warning("Пользователь %s не найден", discord_id)
            return None


# ------------------------------------------------------------------ вспомогательное


async def _safe(interaction: discord.Interaction, awaitable: Any) -> Any:
    """Выполняет запрос, отвечая на недоступность API понятным текстом."""
    try:
        return await awaitable
    except ApiUnavailable:
        await interaction.response.send_message(texts.API_UNAVAILABLE, ephemeral=True)
        return None


async def _screen(interaction: discord.Interaction, api: KoFAuthApi, title: str,
                  awaitable: Any, render: Any) -> None:
    result = await _safe(interaction, awaitable)
    if result is None:
        return
    if not result.ok:
        await interaction.response.send_message(
            texts.NOT_LINKED.format(command="/discord"), ephemeral=True
        )
        return
    await interaction.response.send_message(
        embed=embed(title, render(result.data)), ephemeral=True
    )


def register_message_handlers(bot: DiscordBot, listener: OutboxListener) -> None:
    """Подписывает бота на очередь сообщений.

    Discord обрабатывается как Discord: платформа приходит в самом сообщении и
    используется и при чтении очереди, и при отправке решения. Прежде запрос
    подтверждения приезжал общим событием, а решение уходило на сервер без
    указания платформы — и записывалось как телеграмное.

    Доставка «не меньше одного раза»: одно и то же сообщение может прийти
    дважды, если бот упал между обработкой и подтверждением. Это безопасно —
    повторно показанная кнопка ведёт к тому же самому запросу.
    """

    async def on_approval(message: BotMessage) -> None:
        if not message.get("approvalId") or not message.recipient_id:
            return
        await bot.request_approval(message)

    async def on_resolved(message: BotMessage) -> None:
        LOGGER.debug("Запрос %s закрыт со статусом %s",
                     message.get("approvalId"), message.get("status"))

    async def on_notice(message: BotMessage) -> None:
        notice = NOTICES.get(message.get("event"))
        if notice is None or not message.recipient_id:
            return
        title, template = notice
        await bot.notify(message.recipient_id, title, [
            template.format(
                username=message.get("username", ""),
                ip=message.get("ip", "—"),
                at=texts.format_time(message.get("at")),
            )
        ])

    listener.on(event_kinds.LOGIN_APPROVAL, on_approval)
    listener.on(event_kinds.LOGIN_APPROVAL_RESOLVED, on_resolved)
    listener.on(event_kinds.LOGIN_NOTICE, on_notice)
    listener.on(event_kinds.SECURITY_NOTICE, on_notice)


NOTICES = {
    "AccountLoginEvent": ("🔓 Вход в аккаунт", "Ник: {username}\nАдрес: {ip}\n{at}"),
    "PasswordChangedEvent": ("🔑 Пароль изменён",
                             "{at}\n\nЕсли это были не вы — обратитесь к администрации."),
    "SuspiciousActivityEvent": ("⚠️ Подозрительная активность",
                                "Ник: {username}\nАдрес: {ip}\n{at}"),
    "SessionInvalidatedEvent": ("🚪 Сессии завершены", "{at}"),
    "BindingChangedEvent": ("🔗 Привязки изменены", "{at}"),
}
