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


def embed(
    title: str,
    lines: list[str],
    colour: int = texts.BRAND_COLOUR,
    *,
    banner: str = "",
) -> discord.Embed:
    """Единое оформление экранов.

    Цвет полосы — фирменный красный KoF: это единственное место, где фирменный
    цвет в Discord вообще доступен, разметка сообщений цветов не знает.

    :param banner: ссылка на картинку шапки. Ставится только на главный экран:
        на каждом подэкране она вытеснила бы содержимое за пределы видимого
        и превратила навигацию в пролистывание.
    """
    result = discord.Embed(
        title=title, description="\n".join(lines), colour=discord.Colour(colour)
    )
    if banner:
        result.set_image(url=banner)
    return result


class Brand:
    """Оформление, общее для всех экранов.

    Собрано в один объект, чтобы каждое представление не таскало по отдельности
    ссылку на кабинет, баннер и адрес доната: их приходится передавать через всю
    цепочку экранов, и четвёртый параметр подряд неизбежно теряется в одной из
    веток «назад».
    """

    __slots__ = ("panel_url", "banner_url", "donate_url")

    def __init__(self, panel_url: str, banner_url: str = "", donate_url: str = "") -> None:
        self.panel_url = panel_url
        self.banner_url = banner_url
        self.donate_url = donate_url


class MenuView(discord.ui.View):
    """Главное меню.

    Раздела «Устройства» здесь больше нет: он показывал те же адреса, что и
    «История», только в другом порядке, и ни одного действия к ним не
    прилагалось.

    :param user_id: кому принадлежит меню; чужие нажатия отклоняются
    """

    def __init__(self, api: KoFAuthApi, user_id: int, linked: bool,
                 brand: Brand) -> None:
        super().__init__(timeout=MENU_TIMEOUT)
        self._api = api
        self._user_id = user_id
        self._brand = brand
        if not linked:
            # Непривязанному человеку остальные кнопки ответят одним и тем же
            # «аккаунт не привязан» — показывать их незачем.
            self.clear_items()
            self.add_item(HelpButton(brand))
        if brand.donate_url:
            self.add_item(discord.ui.Button(
                label="Поддержать сервер", emoji=texts.ICON["donate"],
                style=discord.ButtonStyle.link, url=brand.donate_url, row=2,
            ))

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

    @discord.ui.button(label="Профиль", emoji=texts.ICON["profile"],
                       style=discord.ButtonStyle.primary)
    async def profile(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        await self._screen(
            interaction, f"{texts.ICON['profile']} Профиль",
            self._api.account(PLATFORM, self._user_id),
            lambda data: texts.profile_lines(data, donate_url=self._brand.donate_url),
        )

    @discord.ui.button(label="Защита", emoji=texts.ICON["security"],
                       style=discord.ButtonStyle.primary)
    async def security(self, interaction: discord.Interaction,
                       button: discord.ui.Button) -> None:
        result = await self._fetch(interaction, self._api.account(PLATFORM, self._user_id))
        if result is None:
            return
        await interaction.response.edit_message(
            embed=embed(f"{texts.ICON['security']} Защита аккаунта",
                        texts.security_lines(result)),
            view=SecurityView(self._api, self._user_id, bool(result.get("loginApproval")),
                              self._brand),
        )

    @discord.ui.button(label="История", emoji=texts.ICON["history"],
                       style=discord.ButtonStyle.secondary)
    async def history(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        await self._screen(interaction, f"{texts.ICON['history']} Последние входы",
                           self._api.history(PLATFORM, self._user_id),
                           lambda data: texts.history_lines(data.get("history", [])))

    @discord.ui.button(label="Сессии", emoji=texts.ICON["sessions"],
                       style=discord.ButtonStyle.secondary)
    async def sessions(self, interaction: discord.Interaction,
                       button: discord.ui.Button) -> None:
        await self._screen(interaction, f"{texts.ICON['sessions']} Активные сессии",
                           self._api.sessions(PLATFORM, self._user_id),
                           lambda data: texts.session_lines(data.get("sessions", [])))

    # ------------------------------------------------------------------ общее

    async def _screen(self, interaction: discord.Interaction, title: str,
                      awaitable: Awaitable[Any],
                      render: Callable[[dict[str, Any]], list[str]]) -> None:
        result = await self._fetch(interaction, awaitable)
        if result is None:
            return
        await interaction.response.edit_message(
            embed=embed(title, render(result)),
            view=BackView(self._api, self._user_id, self._brand),
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


def home_lines(username: str | None) -> list[str]:
    """Содержимое главного экрана."""
    if username:
        return [
            f"{texts.ICON['profile']} Аккаунт: **{username}**",
            "",
            "Выберите раздел.",
        ]
    return [
        "Аккаунт не привязан.",
        "",
        f"{texts.ICON['link']} Возьмите код в игре командой `/discord` "
        "и пришлите мне `/link КОД`.",
    ]


def home_embed(username: str | None, brand: Brand) -> discord.Embed:
    """Главный экран — единственный, на котором показывается баннер."""
    return embed(texts.BRAND_TITLE, home_lines(username), banner=brand.banner_url)


class BackView(discord.ui.View):
    """Экран с одной кнопкой возврата."""

    def __init__(self, api: KoFAuthApi, user_id: int, brand: Brand) -> None:
        super().__init__(timeout=MENU_TIMEOUT)
        self._api = api
        self._user_id = user_id
        self._brand = brand

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        return interaction.user.id == self._user_id

    @discord.ui.button(label="Назад", emoji=texts.ICON["back"],
                       style=discord.ButtonStyle.secondary)
    async def back(self, interaction: discord.Interaction,
                   button: discord.ui.Button) -> None:
        result = await self._api.account(PLATFORM, self._user_id)
        linked = result.ok
        await interaction.response.edit_message(
            embed=home_embed(result.data.get("username") if linked else None, self._brand),
            view=MenuView(self._api, self._user_id, linked, self._brand),
        )


class SecurityView(discord.ui.View):
    """Экран защиты: переключатели и опасные действия."""

    def __init__(self, api: KoFAuthApi, user_id: int, login_approval: bool,
                 brand: Brand) -> None:
        super().__init__(timeout=MENU_TIMEOUT)
        self._api = api
        self._user_id = user_id
        self._brand = brand
        self._login_approval = login_approval

        # Надпись описывает действие, а не состояние: «Выключить» рядом со
        # словом «включено» читается однозначно.
        self.toggle.label = (
            "Выключить подтверждение" if login_approval else "Включить подтверждение"
        )
        self.toggle.emoji = (
            texts.ICON["bell_off"] if login_approval else texts.ICON["bell_on"]
        )

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
            embed=embed(f"{texts.ICON['security']} Защита аккаунта",
                        texts.security_lines(result.data)),
            view=SecurityView(self._api, self._user_id,
                              bool(result.data.get("loginApproval")), self._brand),
        )

    @discord.ui.button(label="Выйти со всех устройств", emoji=texts.ICON["logout"],
                       style=discord.ButtonStyle.secondary)
    async def logout(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        result = await self._api.logout_all(PLATFORM, self._user_id)
        await interaction.response.send_message(
            f"{texts.ICON['approve']} Завершено сессий: {result.data.get('revoked', 0)}"
            if result.ok else texts.describe_error(result.error),
            ephemeral=True,
        )

    @discord.ui.button(label="Отвязать Discord", emoji=texts.ICON["unlink"],
                       style=discord.ButtonStyle.danger)
    async def unlink(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        # Отвязка выключает второй фактор и уведомления разом, то есть тихо
        # снижает защиту аккаунта. Один промах по кнопке не должен её выполнить.
        await interaction.response.edit_message(
            embed=embed(
                f"{texts.ICON['warning']} Отвязать Discord?",
                ["Подтверждение входа и уведомления перестанут работать."],
                colour=texts.DANGER_COLOUR,
            ),
            view=ConfirmUnlinkView(self._api, self._user_id, self._brand),
        )

    @discord.ui.button(label="Назад", emoji=texts.ICON["back"],
                       style=discord.ButtonStyle.secondary, row=1)
    async def back(self, interaction: discord.Interaction,
                   button: discord.ui.Button) -> None:
        result = await self._api.account(PLATFORM, self._user_id)
        await interaction.response.edit_message(
            embed=home_embed(result.data.get("username") if result.ok else None,
                             self._brand),
            view=MenuView(self._api, self._user_id, result.ok, self._brand),
        )


class ConfirmUnlinkView(discord.ui.View):
    """Подтверждение отвязки."""

    def __init__(self, api: KoFAuthApi, user_id: int, brand: Brand) -> None:
        super().__init__(timeout=60.0)
        self._api = api
        self._user_id = user_id
        self._brand = brand

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        return interaction.user.id == self._user_id

    @discord.ui.button(label="Да, отвязать", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        result = await self._api.unlink(PLATFORM, self._user_id)
        if not result.ok:
            await interaction.response.edit_message(
                embed=embed(texts.BRAND_TITLE, [texts.describe_error(result.error)],
                            colour=texts.DANGER_COLOUR),
                view=MenuView(self._api, self._user_id, True, self._brand),
            )
            return
        await interaction.response.edit_message(
            embed=home_embed(None, self._brand),
            view=MenuView(self._api, self._user_id, False, self._brand),
        )

    @discord.ui.button(label="Отмена", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction: discord.Interaction,
                     button: discord.ui.Button) -> None:
        result = await self._api.account(PLATFORM, self._user_id)
        await interaction.response.edit_message(
            embed=embed(f"{texts.ICON['security']} Защита аккаунта",
                        texts.security_lines(result.data)),
            view=SecurityView(self._api, self._user_id,
                              bool(result.data.get("loginApproval")), self._brand),
        )


class HelpButton(discord.ui.Button):
    """Кнопка справки для непривязанного человека."""

    def __init__(self, brand: Brand) -> None:
        super().__init__(label="Как привязать аккаунт", emoji=texts.ICON["link"],
                         style=discord.ButtonStyle.primary)
        self._brand = brand

    async def callback(self, interaction: discord.Interaction) -> None:
        lines = [
            "1. Зайдите в игру",
            "2. Наберите `/discord`",
            "3. Нажмите на ссылку в чате — она откроет канал привязки",
            "4. Пришлите мне `/link КОД`",
            "",
            "Код выдаётся только в игре — это единственный способ доказать, "
            "что аккаунт ваш.",
            "",
            f"{texts.ICON['site']} Личный кабинет: {self._brand.panel_url}",
        ]
        if self._brand.donate_url:
            lines.append(
                f"{texts.ICON['donate']} Поддержать сервер: {self._brand.donate_url}"
            )
        await interaction.response.edit_message(
            embed=embed(f"{texts.ICON['link']} Как привязать аккаунт", lines),
            view=None,
        )


class ApprovalView(discord.ui.View):
    """Кнопки подтверждения входа.

    Ровно две и никаких кодов. Идентификатор запроса хранится в самом объекте
    представления, но решение принимает сервер: он же и сверяет, что нажал тот,
    кому кнопка адресована.

    Проверка владельца выполняется дважды — здесь и на сервере. Здешняя
    проверка нужна, чтобы человек сразу получил внятный ответ вместо отказа
    без объяснений; серверная — потому что клиентской доверять нельзя, а
    сообщение с кнопками можно переслать.

    Таймаут совпадает со сроком жизни запроса: держать нажимаемыми кнопки,
    за которыми уже нет действующего запроса, — значит обещать несбыточное.
    """

    def __init__(self, api: KoFAuthApi, approval_id: str, recipient_id: int,
                 timeout: float = 120.0) -> None:
        super().__init__(timeout=timeout)
        self._api = api
        self._approval_id = approval_id
        self._recipient_id = recipient_id

    @discord.ui.button(label="Войти", emoji=texts.ICON["approve"],
                       style=discord.ButtonStyle.success)
    async def approve(self, interaction: discord.Interaction,
                      button: discord.ui.Button) -> None:
        await self._decide(interaction, approved=True)

    @discord.ui.button(label="Отклонить", emoji=texts.ICON["deny"],
                       style=discord.ButtonStyle.danger)
    async def deny(self, interaction: discord.Interaction,
                   button: discord.ui.Button) -> None:
        await self._decide(interaction, approved=False)

    async def _decide(self, interaction: discord.Interaction, approved: bool) -> None:
        if interaction.user.id != self._recipient_id:
            await interaction.response.send_message(
                "Эта кнопка адресована другому человеку.", ephemeral=True
            )
            return

        try:
            result = await self._api.approve(
                PLATFORM, interaction.user.id, self._approval_id, approved
            )
        except ApiUnavailable:
            # Сообщение не правим: запрос, возможно, ещё жив, и человек сможет
            # нажать снова.
            await interaction.response.send_message(texts.API_UNAVAILABLE, ephemeral=True)
            return

        outcome = str(result.data.get("result") or "")
        text = approval_outcome_text(outcome, approved)

        if outcome == "FOREIGN":
            await interaction.response.send_message(text, ephemeral=True)
            return

        # Кнопки убираем: решение принято, нажимать больше нечего.
        await interaction.response.edit_message(
            embed=embed(f"{texts.ICON['lock']} Подтверждение входа", [text],
                        colour=texts.BRAND_COLOUR if approved else texts.DANGER_COLOUR),
            view=None,
        )


def approval_outcome_text(outcome: str, approved: bool) -> str:
    """Человеческое описание исхода нажатия."""
    if outcome == "APPLIED":
        return (
            "Вход подтверждён." if approved
            else "Вход отклонён. Если это были не вы — смените пароль."
        )
    if outcome == "ALREADY_DECIDED":
        return "Этот запрос уже обработан."
    if outcome == "EXPIRED":
        return "Время подтверждения истекло. Зайдите в игру ещё раз."
    if outcome == "FOREIGN":
        return "Эта кнопка адресована другому человеку."
    if outcome == "NOT_FOUND":
        return "Запрос не найден: возможно, он уже устарел."
    return "Не получилось обработать нажатие."
