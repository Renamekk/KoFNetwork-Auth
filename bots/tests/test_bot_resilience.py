"""Поведение ботов при отказах API и при неполном ответе сервера.

Проверяется не «красиво ли выглядит ошибка», а два свойства, без которых
интерфейс перестаёт работать молча.

**Отказ API не ломает экран.** WebAPI перезапускают, сеть моргает. Обработчик
кнопки, из которого исключение уходит наружу, оставляет человека перед
сообщением, кнопки которого выглядят рабочими: Discord показывает «This
interaction failed», Telegram крутит часы до таймаута. Ответить нечем и
повторить нечего.

**Кнопки подтверждения входа переживают невнятный ответ.** Кнопки убирают,
только когда решение действительно записано. Любой другой исход — неверный ключ
бота, отвергнутый запрос, незнакомый ответ — означает, что запрос, возможно, ещё
жив; убрать кнопки в этом случае значит отнять у человека единственный способ
войти, ничего не дав взамен.
"""

from __future__ import annotations

from typing import Any

from discord_bot import views
from kofauth_common.api import ApiUnavailable
from telegram_bot.bot import DECIDED as TELEGRAM_DECIDED
from telegram_bot.bot import TelegramBot

from test_cards_screens import Interaction, Painter, telegram_bot

PROFILE = {
    "username": "Inroka",
    "registeredAt": "2026-02-01T09:14:00Z",
    "twoFactor": ["DISCORD"],
    "loginApproval": True,
}


class Result:
    """Ответ API: успешный либо отказ по существу."""

    def __init__(self, ok: bool, data: dict[str, Any] | None = None,
                 error: str = "") -> None:
        self.ok = ok
        self.data = data if data is not None else {}
        self.error = error


class DeadApi:
    """Клиент, у которого не отвечает ничего: каждый вызов — ApiUnavailable."""

    def __init__(self) -> None:
        self.calls = 0

    async def _fail(self, *args: Any, **kwargs: Any):
        self.calls += 1
        raise ApiUnavailable("сеть недоступна")

    account = _fail
    history = _fail
    sessions = _fail
    unlink = _fail
    logout_all = _fail
    set_login_approval = _fail
    approve = _fail


class RefusingApi:
    """Клиент, отвечающий отказом по существу, а не сбоем транспорта."""

    def __init__(self, error: str = "NOT_LINKED") -> None:
        self._error = error

    async def account(self, *args: Any, **kwargs: Any) -> Result:
        return Result(False, {"code": self._error}, self._error)

    async def unlink(self, *args: Any, **kwargs: Any) -> Result:
        return Result(False, {"code": self._error}, self._error)

    async def set_login_approval(self, *args: Any, **kwargs: Any) -> Result:
        return Result(False, {"code": self._error}, self._error)

    async def logout_all(self, *args: Any, **kwargs: Any) -> Result:
        return Result(False, {"code": self._error}, self._error)


def brand() -> views.Brand:
    return views.Brand(panel_url="http://panel", cards=Painter())


# --------------------------------------------------------------------- Discord


class TestНедоступныйApiDiscord:
    """Ни одна кнопка не выпускает исключение наружу.

    Регрессия: обёртку имел только главный экран. Возврат, переключатель защиты,
    выход со всех устройств, отвязка и отмена отвязки обращались к API напрямую,
    и отказ сети превращал экран в неотвечающий.
    """

    async def test_возврат_отвечает_вместо_исключения(self) -> None:
        view = views.BackView(DeadApi(), 2002, brand())
        interaction = Interaction()

        await view.back.callback(interaction)

        assert interaction.response.sent is not None
        assert interaction.response.edited is None

    async def test_переключатель_защиты_отвечает_вместо_исключения(self) -> None:
        view = views.SecurityView(DeadApi(), 2002, True, brand())
        interaction = Interaction()

        await view.toggle.callback(interaction)

        assert interaction.response.sent is not None

    async def test_выход_со_всех_устройств_отвечает_вместо_исключения(self) -> None:
        view = views.SecurityView(DeadApi(), 2002, True, brand())
        interaction = Interaction()

        await view.logout.callback(interaction)

        assert interaction.response.sent is not None

    async def test_возврат_из_защиты_отвечает_вместо_исключения(self) -> None:
        view = views.SecurityView(DeadApi(), 2002, True, brand())
        interaction = Interaction()

        await view.back.callback(interaction)

        assert interaction.response.sent is not None

    async def test_подтверждение_отвязки_отвечает_вместо_исключения(self) -> None:
        view = views.ConfirmUnlinkView(DeadApi(), 2002, brand())
        interaction = Interaction()

        await view.confirm.callback(interaction)

        assert interaction.response.sent is not None

    async def test_отмена_отвязки_отвечает_вместо_исключения(self) -> None:
        view = views.ConfirmUnlinkView(DeadApi(), 2002, brand())
        interaction = Interaction()

        await view.cancel.callback(interaction)

        assert interaction.response.sent is not None


class TestОтказПоСуществуDiscord:
    async def test_отмена_отвязки_не_строит_карточку_из_ответа_об_ошибке(self) -> None:
        # Регрессия: ответ об отказе передавался в security_card как профиль, и
        # человек видел экран «Защита», где всё выключено и ничего не привязано,
        # хотя на деле сервер просто не ответил профилем.
        view = views.ConfirmUnlinkView(RefusingApi(), 2002, brand())
        interaction = Interaction()

        await view.cancel.callback(interaction)

        edited = interaction.response.edited
        assert edited is not None
        assert not isinstance(edited["view"], views.SecurityView)
        assert "Защита" not in edited["embed"].title


class TestКнопкиПодтвержденияDiscord:
    """Кнопки убираются только по записанному решению."""

    class ApproveApi:
        def __init__(self, payload: dict[str, Any]) -> None:
            self._payload = payload

        async def approve(self, *args: Any, **kwargs: Any) -> Result:
            return Result(True, self._payload)

    async def test_записанное_решение_убирает_кнопки(self) -> None:
        view = views.ApprovalView(self.ApproveApi({"result": "APPLIED"}),
                                  "запрос-1", 2002)
        interaction = Interaction()

        await view.approve.callback(interaction)

        edited = interaction.response.edited
        assert edited is not None
        assert edited["view"] is None

    async def test_невнятный_ответ_кнопки_сохраняет(self) -> None:
        # Ключ бота отвергнут, тело без поля result. Запрос, возможно, ещё жив.
        view = views.ApprovalView(self.ApproveApi({"code": "BOT_UNAUTHORIZED"}),
                                  "запрос-1", 2002)
        interaction = Interaction()

        await view.approve.callback(interaction)

        assert interaction.response.edited is None, "кнопки обязаны остаться"
        assert interaction.response.sent is not None

    async def test_недоступный_api_кнопки_сохраняет(self) -> None:
        view = views.ApprovalView(DeadApi(), "запрос-1", 2002)
        interaction = Interaction()

        await view.approve.callback(interaction)

        assert interaction.response.edited is None
        assert interaction.response.sent is not None

    async def test_чужое_нажатие_не_трогает_сообщение(self) -> None:
        view = views.ApprovalView(self.ApproveApi({"result": "APPLIED"}),
                                  "запрос-1", 2002)
        interaction = Interaction(user_id=9999)

        await view.approve.callback(interaction)

        assert interaction.response.edited is None

    def test_перечень_закрывающих_исходов_полон(self) -> None:
        # Совпадает с DecisionResult на стороне Core за вычетом FOREIGN,
        # который разбирается отдельно: чужое сообщение править нельзя.
        assert views.DECIDED == {"APPLIED", "ALREADY_DECIDED", "EXPIRED", "NOT_FOUND"}


# -------------------------------------------------------------------- Telegram


class FakeQuery:
    """Нажатие кнопки Telegram, записывающее ответы бота."""

    def __init__(self, data: str, user_id: int = 2002) -> None:
        self.data = data
        self.from_user = type("User", (), {"id": user_id})()
        self.message = None
        self.answers: list[tuple[str, bool]] = []

    async def answer(self, text: str = "", show_alert: bool = False, **kwargs: Any) -> None:
        self.answers.append((text, show_alert))


class ApprovingBot:
    """Клиент API с заданным исходом нажатия."""

    def __init__(self, payload: dict[str, Any] | None = None,
                 unavailable: bool = False) -> None:
        self._payload = payload or {}
        self._unavailable = unavailable

    async def approve(self, *args: Any, **kwargs: Any) -> Result:
        if self._unavailable:
            raise ApiUnavailable("сеть недоступна")
        return Result(True, self._payload)


def bot_with(api: Any) -> TelegramBot:
    bot = telegram_bot()
    bot._api = api
    return bot


class TestКнопкиПодтвержденияTelegram:
    async def test_невнятный_ответ_кнопки_сохраняет(self) -> None:
        bot = bot_with(ApprovingBot({"code": "BOT_UNAUTHORIZED"}))
        query = FakeQuery("ok:запрос-1")

        await bot._on_approval(query)

        # Сообщение не перерисовано: у запроса, возможно, ещё есть время.
        assert query.message is None
        assert query.answers and query.answers[0][1] is True

    async def test_недоступный_api_кнопки_сохраняет(self) -> None:
        bot = bot_with(ApprovingBot(unavailable=True))
        query = FakeQuery("no:запрос-1")

        await bot._on_approval(query)

        assert query.answers and query.answers[0][1] is True

    def test_перечень_закрывающих_исходов_совпадает_с_Discord(self) -> None:
        # Один и тот же сервер, одни и те же исходы: расхождение означало бы,
        # что на одной площадке кнопка живёт дольше, чем сам запрос.
        assert TELEGRAM_DECIDED == views.DECIDED
