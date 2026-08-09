"""Меню Discord-бота.

Навигация построена на ``discord.ui.View``: одно эфемерное сообщение, которое
перерисовывается кнопками. Эфемерное — принципиально: профиль, устройства и
история видны только вызвавшему, даже если команда набрана в общем канале.

Отдельная сложность Discord — время жизни View. Компоненты перестают работать
через ``timeout`` секунд, после чего нажатие возвращает ошибку. Поэтому у
меню таймаут задан явно и по его истечении кнопки гасятся, а не остаются
выглядящими рабочими.
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from typing import Any

import discord

from kofauth_common import ApiUnavailable, KoFAuthApi, texts

LOGGER = logging.getLogger(__name__)

PLATFORM = "DISCORD"

#: Столько живёт меню. Больше десяти минут Discord не гарантирует доставку
#: взаимодействия, меньше — человек не успевает прочитать историю входов.
MENU_TIMEOUT = 300.0


def embed(title: str, lines: list[str], colour: int = 0x4FACFE) -> discord.Embed:
    """Единое оформление экранов."""
    return discord.Embed(
        title=title, description="\n".join(lines), colour=discord.Colour(colour)
    )


class MenuView(discord.ui.View):
    """Главное меню.

    :param user_id: кому принадлежит меню; чужие нажатия отклоняются
    """

    def __init__(self, api: KoFAuthApi, user_id: int, linked: bool,
                 panel_url: str) -> None:
        super().__init__(timeout=MENU_TIMEOUT)
        self._api = api
        self._user_id = user_id
        self._panel_url = panel_url
        if not linked:
            # Непривязанному человеку остальные кнопки ответят одним и тем же
            # «аккаунт не привязан» — показывать их незачем.
            self.clear_items()
            self.add_item(HelpButton(panel_url))

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        """Чужое меню нажимать нельзя.

        Сообщение эфемерное, но проверка всё равно нужна: она стоит дёшево,
        а её отсутствие — ровно та ошибка, из-за которой в чужом канале
        показывают чужой профиль.
        """
        if interaction.user.id == self._user_id:
            return True
        await interaction.response.send_message(
            "Это меню открыл другой человек. Наберите /menu.", ephemeral=True
        )
        return False

    async def on_timeout(self) -> None:
        for item in self.children:
            item.disabled = True

    # ------------------------------------------------------------------ кнопки

    @discord.ui.button(label="Профиль", emoji="👤", style=discord.ButtonStyle.primary)
    async def profile(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        await self._screen(interaction, "Профиль", self._api.account(PLATFORM, self._user_id),
                           lambda data: texts.profile_lines(data))

    @discord.ui.button(label="Защита", emoji="🛡", style=discord.ButtonStyle.primary)
    async def security(self, interaction: discord.Interaction,
                       button: discord.ui.Button) -> None:
        result = await self._fetch(interaction, self._api.account(PLATFORM, self._user_id))
        if result is None:
            return
        await interaction.response.edit_message(
            embed=embed("Защита аккаунта", texts.security_lines(result)),
            view=SecurityView(self._api, self._user_id, bool(result.get("loginApproval")),
                              self._panel_url),
        )

    @discord.ui.button(label="Устройства", emoji="💻", style=discord.ButtonStyle.secondary)
    async def devices(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        await self._screen(interaction, "Устройства",
                           self._api.devices(PLATFORM, self._user_id),
                           lambda data: texts.device_lines(data.get("devices", [])))

    @discord.ui.button(label="История", emoji="🕘", style=discord.ButtonStyle.secondary)
    async def history(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        await self._screen(interaction, "Последние входы",
                           self._api.history(PLATFORM, self._user_id),
                           lambda data: texts.history_lines(data.get("history", [])))

    @discord.ui.button(label="Код для входа", emoji="🔢", style=discord.ButtonStyle.success,
                       row=1)
    async def send_code(self, interaction: discord.Interaction,
                        button: discord.ui.Button) -> None:
        result = await self._fetch(interaction, self._api.send_code(PLATFORM, self._user_id))
        if result is None:
            return
        code = result.get("code", "")
        # Код уходит отдельным сообщением: его копируют, и он не должен
        # исчезнуть при следующем шаге по меню.
        await interaction.response.send_message(
            f"🔑 Код подтверждения входа:\n```\n{code}\n```\n"
            f"Введите в игре: `/login пароль {code}`\n"
            f"Действует {int(result.get('expiresInSeconds', 120)) // 60} мин.",
            ephemeral=True,
        )

    # ------------------------------------------------------------------ общее

    async def _screen(self, interaction: discord.Interaction, title: str,
                      awaitable: Awaitable[Any],
                      render: Callable[[dict[str, Any]], list[str]]) -> None:
        result = await self._fetch(interaction, awaitable)
        if result is None:
            return
        await interaction.response.edit_message(
            embed=embed(title, render(result)),
            view=BackView(self._api, self._user_id, self._panel_url),
        )

    async def _fetch(self, interaction: discord.Interaction,
                     awaitable: Awaitable[Any]) -> dict[str, Any] | None:
        """Выполняет запрос, отвечая на отказы понятным текстом."""
        try:
            result = await awaitable
        except ApiUnavailable:
            await interaction.response.send_message(texts.API_UNAVAILABLE, ephemeral=True)
            return None
        if not result.ok:
            await interaction.response.send_message(
                texts.NOT_LINKED.format(command="/discord")
                if result.error == "NOT_LINKED"
                else texts.describe_error(result.error),
                ephemeral=True,
            )
            return None
        return result.data


class BackView(discord.ui.View):
    """Экран с одной кнопкой возврата."""

    def __init__(self, api: KoFAuthApi, user_id: int, panel_url: str) -> None:
        super().__init__(timeout=MENU_TIMEOUT)
        self._api = api
        self._user_id = user_id
        self._panel_url = panel_url

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        return interaction.user.id == self._user_id

    @discord.ui.button(label="Назад", emoji="◀", style=discord.ButtonStyle.secondary)
    async def back(self, interaction: discord.Interaction,
                   button: discord.ui.Button) -> None:
        result = await self._api.account(PLATFORM, self._user_id)
        linked = result.ok
        lines = (
            [f"Аккаунт: **{result.data.get('username', '—')}**", "", "Выберите раздел."]
            if linked
            else ["Аккаунт не привязан.", "", "Возьмите код в игре командой `/discord`."]
        )
        await interaction.response.edit_message(
            embed=embed("KoF Network", lines),
            view=MenuView(self._api, self._user_id, linked, self._panel_url),
        )


class SecurityView(discord.ui.View):
    """Экран защиты: переключатели и опасные действия."""

    def __init__(self, api: KoFAuthApi, user_id: int, login_approval: bool,
                 panel_url: str) -> None:
        super().__init__(timeout=MENU_TIMEOUT)
        self._api = api
        self._user_id = user_id
        self._panel_url = panel_url
        self._login_approval = login_approval

        # Надпись описывает действие, а не состояние: «Выключить» рядом со
        # словом «включено» читается однозначно.
        self.toggle.label = (
            "Выключить подтверждение" if login_approval else "Включить подтверждение"
        )
        self.toggle.emoji = "🔕" if login_approval else "🔔"

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        return interaction.user.id == self._user_id

    @discord.ui.button(style=discord.ButtonStyle.primary)
    async def toggle(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        result = await self._api.set_login_approval(
            PLATFORM, self._user_id, not self._login_approval
        )
        if not result.ok:
            await interaction.response.send_message(
                texts.describe_error(result.error), ephemeral=True
            )
            return
        await interaction.response.edit_message(
            embed=embed("Защита аккаунта", texts.security_lines(result.data)),
            view=SecurityView(self._api, self._user_id,
                              bool(result.data.get("loginApproval")), self._panel_url),
        )

    @discord.ui.button(label="Выйти со всех устройств", emoji="🚪",
                       style=discord.ButtonStyle.secondary)
    async def logout(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        result = await self._api.logout_all(PLATFORM, self._user_id)
        await interaction.response.send_message(
            f"Завершено сессий: {result.data.get('revoked', 0)}" if result.ok
            else texts.describe_error(result.error),
            ephemeral=True,
        )

    @discord.ui.button(label="Отвязать Discord", emoji="🔓", style=discord.ButtonStyle.danger)
    async def unlink(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        # Отвязка выключает второй фактор и уведомления разом, то есть тихо
        # снижает защиту аккаунта. Один промах по кнопке не должен её выполнить.
        await interaction.response.edit_message(
            embed=embed(
                "Отвязать Discord?",
                ["Подтверждение входа и уведомления перестанут работать."],
                colour=0xE8505B,
            ),
            view=ConfirmUnlinkView(self._api, self._user_id, self._panel_url),
        )

    @discord.ui.button(label="Назад", emoji="◀", style=discord.ButtonStyle.secondary, row=1)
    async def back(self, interaction: discord.Interaction,
                   button: discord.ui.Button) -> None:
        result = await self._api.account(PLATFORM, self._user_id)
        await interaction.response.edit_message(
            embed=embed("KoF Network",
                        [f"Аккаунт: **{result.data.get('username', '—')}**", "",
                         "Выберите раздел."]),
            view=MenuView(self._api, self._user_id, result.ok, self._panel_url),
        )


class ConfirmUnlinkView(discord.ui.View):
    """Подтверждение отвязки."""

    def __init__(self, api: KoFAuthApi, user_id: int, panel_url: str) -> None:
        super().__init__(timeout=60.0)
        self._api = api
        self._user_id = user_id
        self._panel_url = panel_url

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        return interaction.user.id == self._user_id

    @discord.ui.button(label="Да, отвязать", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        result = await self._api.unlink(PLATFORM, self._user_id)
        await interaction.response.edit_message(
            embed=embed(
                "KoF Network",
                ["Аккаунт отвязан."] if result.ok
                else [texts.describe_error(result.error)],
            ),
            view=MenuView(self._api, self._user_id, not result.ok, self._panel_url),
        )

    @discord.ui.button(label="Отмена", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        result = await self._api.account(PLATFORM, self._user_id)
        await interaction.response.edit_message(
            embed=embed("Защита аккаунта", texts.security_lines(result.data)),
            view=SecurityView(self._api, self._user_id,
                              bool(result.data.get("loginApproval")), self._panel_url),
        )


class HelpButton(discord.ui.Button):
    """Кнопка справки для непривязанного человека."""

    def __init__(self, panel_url: str) -> None:
        super().__init__(label="Как привязать аккаунт", emoji="🔗",
                         style=discord.ButtonStyle.primary)
        self._panel_url = panel_url

    async def callback(self, interaction: discord.Interaction) -> None:
        await interaction.response.edit_message(
            embed=embed("Как привязать аккаунт", [
                "1. Зайдите в игру",
                "2. Наберите `/discord`",
                "3. Пришлите мне `/link КОД`",
                "",
                "Код выдаётся только в игре — это единственный способ доказать, "
                "что аккаунт ваш.",
                "",
                f"Личный кабинет: {self._panel_url}",
            ]),
            view=None,
        )


class ApprovalView(discord.ui.View):
    """Кнопки подтверждения входа.

    Таймаут совпадает со сроком жизни токена: держать нажимаемыми кнопки,
    за которыми уже нет действующего токена, — значит обещать несбыточное.
    """

    def __init__(self, api: KoFAuthApi, token: str, timeout: float = 120.0) -> None:
        super().__init__(timeout=timeout)
        self._api = api
        self._token = token

    @discord.ui.button(label="Это я", emoji="✅", style=discord.ButtonStyle.success)
    async def approve(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        await self._decide(interaction, approved=True)

    @discord.ui.button(label="Это не я", emoji="❌", style=discord.ButtonStyle.danger)
    async def deny(self, interaction: discord.Interaction,
                   button: discord.ui.Button) -> None:
        await self._decide(interaction, approved=False)

    async def _decide(self, interaction: discord.Interaction, approved: bool) -> None:
        try:
            result = await self._api.approve(self._token, approved)
        except ApiUnavailable:
            await interaction.response.send_message(texts.API_UNAVAILABLE, ephemeral=True)
            return

        if not approved:
            text = "Вход отклонён. Если это были не вы — смените пароль."
        elif result.ok and result.data.get("ok"):
            text = "Вход подтверждён."
        else:
            text = "Запрос устарел или уже обработан."

        # Кнопки убираем: токен погашен, нажимать больше нечего.
        await interaction.response.edit_message(
            embed=embed("Подтверждение входа", [text]), view=None
        )
