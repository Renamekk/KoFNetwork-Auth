"""Оформление кнопок и фирменные emoji.

Проверяется не красота, а то, что её нельзя сломать незаметно: набор стилей у
Discord, длина ``callback_data`` у Telegram, единый источник значков у обоих и
поведение при ненастроенных или испорченных фирменных emoji.

Сами вызовы Telegram и Discord по-прежнему не тестируются: раскладка кнопок —
чистая функция от состояния, и её видно без сети.
"""

from __future__ import annotations

import discord
import pytest
from discord_bot import views
from kofauth_common.api import KoFAuthApi
from kofauth_common.config import ApiSettings, BrandSettings
from kofauth_common.glyphs import DEFAULT, FALLBACK, EmojiSet, strip_custom_emoji
from telegram_bot import menu

#: Ни разу не настоящие идентификаторы, и в коде ботов таких нет: их выдаёт
#: платформа, а выдуманный номер показал бы пустой квадрат вместо картинки.
#: Здесь они годятся — проверяется разбор строки, а не сама картинка.
DISCORD_ID = "123456789012345678"
TELEGRAM_ID = "5368324170671202286"


def api() -> KoFAuthApi:
    return KoFAuthApi(ApiSettings(base_url="http://webapi:8080", bot_key="secret"))


def brand(emoji: EmojiSet = DEFAULT, donate_url: str = "") -> views.Brand:
    return views.Brand(panel_url="http://panel", donate_url=donate_url, emoji=emoji)


def buttons(view: discord.ui.View) -> list[discord.ui.Button]:
    return [item for item in view.children if isinstance(item, discord.ui.Button)]


def telegram_buttons(markup) -> list:
    return [button for row in markup.inline_keyboard for button in row]


# --------------------------------------------------------------------- Discord


class TestСтилиDiscord:
    """Палитра сети — красный и нейтральный. Синего и зелёного нет нигде."""

    ALLOWED = {
        discord.ButtonStyle.danger,
        discord.ButtonStyle.secondary,
        discord.ButtonStyle.link,
    }

    def every_view(self) -> list[discord.ui.View]:
        client = api()
        return [
            views.MenuView(client, 1, True, brand(donate_url="https://kof.net/donate")),
            views.MenuView(client, 1, False, brand()),
            views.BackView(client, 1, brand()),
            views.SecurityView(client, 1, True, brand()),
            views.SecurityView(client, 1, False, brand()),
            views.ConfirmUnlinkView(client, 1, brand()),
            views.ApprovalView(client, "запрос-1", 1),
        ]

    def test_синих_и_зелёных_кнопок_нет(self) -> None:
        # Четыре разных цвета в одном меню читаются как четыре разных
        # приложения. Красный — фирменный, secondary — ближайший к белому.
        for view in self.every_view():
            for button in buttons(view):
                assert button.style in self.ALLOWED, button.label

    def test_у_каждой_кнопки_есть_значок(self) -> None:
        # Кнопка без значка в ряду со значками выглядит как недогрузившаяся.
        for view in self.every_view():
            for button in buttons(view):
                assert button.emoji is not None, button.label

    def test_главные_входы_красные_а_навигация_нейтральная(self) -> None:
        view = views.MenuView(api(), 1, True, brand())
        style = {button.label: button.style for button in buttons(view)}

        assert style["Профиль"] == views.ACCENT
        assert style["Защита"] == views.ACCENT
        assert style["История"] == views.NEUTRAL
        assert style["Сессии"] == views.NEUTRAL

    def test_у_непривязанного_одна_красная_кнопка(self) -> None:
        view = views.MenuView(api(), 1, False, brand())
        assert [(b.label, b.style) for b in buttons(view)] == [
            ("Как привязать аккаунт", views.ACCENT)
        ]

    def test_отвязка_стоит_отдельной_строкой(self) -> None:
        # Красный у Discord один на «главное» и на «опасно». В общей строке они
        # бы слились, а промах попадал бы в отключение второго фактора.
        view = views.SecurityView(api(), 1, True, brand())
        rows = {button.label: button.row for button in buttons(view)}

        assert rows["Отвязать Discord"] not in {rows["Выйти со всех устройств"],
                                                rows["Назад"]}
        assert rows["Назад"] > rows["Отвязать Discord"]

    def test_возврат_везде_нейтральный(self) -> None:
        for view in (views.BackView(api(), 1, brand()),
                     views.SecurityView(api(), 1, True, brand()),
                     views.ConfirmUnlinkView(api(), 1, brand())):
            for button in buttons(view):
                if button.label in {"Назад", "Отмена"}:
                    assert button.style == views.NEUTRAL

    def test_подтверждение_входа_это_две_кнопки_красная_и_нейтральная(self) -> None:
        view = views.ApprovalView(api(), "запрос-1", 1)
        assert [(b.label, b.style) for b in buttons(view)] == [
            ("Войти", views.ACCENT),
            ("Отклонить", views.NEUTRAL),
        ]

    def test_переключатель_описывает_действие(self) -> None:
        # Надпись — про то, что произойдёт, а не про текущее состояние.
        on = views.SecurityView(api(), 1, True, brand())
        off = views.SecurityView(api(), 1, False, brand())

        assert on.toggle.label == "Выключить подтверждение"
        assert off.toggle.label == "Включить подтверждение"
        assert str(on.toggle.emoji) == FALLBACK["bell_off"]
        assert str(off.toggle.emoji) == FALLBACK["bell_on"]


class TestФирменныеEmojiDiscord:
    def test_настроенный_emoji_попадает_на_кнопку(self) -> None:
        emoji = EmojiSet(discord_raw=f"profile=<:kof_profile:{DISCORD_ID}>")
        view = views.MenuView(api(), 1, True, brand(emoji))
        marks = {button.label: button.emoji for button in buttons(view)}

        assert marks["Профиль"].id == int(DISCORD_ID)
        assert marks["Профиль"].name == "kof_profile"
        # Ненастроенные остаются обычными — это и есть «работает без настройки».
        assert str(marks["Защита"]) == FALLBACK["security"]

    def test_анимированный_emoji_остаётся_анимированным(self) -> None:
        emoji = EmojiSet(discord_raw=f"brand=<a:kof_flame:{DISCORD_ID}>")
        assert emoji.discord("brand") == f"<a:kof_flame:{DISCORD_ID}>"

    def test_форма_без_угловых_скобок_понимается(self) -> None:
        emoji = EmojiSet(discord_raw=f"donate=kof_heart:{DISCORD_ID}")
        assert emoji.discord("donate") == f"<:kof_heart:{DISCORD_ID}>"

    def test_кнопка_ссылка_тоже_получает_значок(self) -> None:
        emoji = EmojiSet(discord_raw=f"donate=<:kof_heart:{DISCORD_ID}>")
        view = views.MenuView(api(), 1, True,
                              brand(emoji, donate_url="https://kof.net/donate"))
        donate = next(b for b in buttons(view) if b.label == "Поддержать сервер")

        assert donate.style == discord.ButtonStyle.link
        assert donate.emoji.id == int(DISCORD_ID)

    def test_подтверждение_входа_получает_значки(self) -> None:
        emoji = EmojiSet(discord_raw=f"approve=<:kof_key:{DISCORD_ID}>")
        view = views.ApprovalView(api(), "запрос-1", 1, emoji=emoji)

        assert buttons(view)[0].emoji.id == int(DISCORD_ID)
        assert str(buttons(view)[1].emoji) == FALLBACK["deny"]


# -------------------------------------------------------------------- Telegram


class TestКнопкиTelegram:
    def every_markup(self) -> list:
        return [
            menu.main_menu(True, donate_url="https://kof.net/donate"),
            menu.main_menu(False, donate_url="https://kof.net/donate"),
            menu.back_only(),
            menu.security_menu(True),
            menu.security_menu(False),
            menu.confirm_unlink(),
            menu.approval_keyboard("A" * 22),
        ]

    def test_callback_data_везде_умещается_в_ограничение(self) -> None:
        # Telegram отвергает callback_data длиннее 64 байт, и отвергает молча
        # для того, кто её собрал: ошибку видит только игрок.
        for markup in self.every_markup():
            for button in telegram_buttons(markup):
                if button.callback_data:
                    assert len(button.callback_data.encode("utf-8")) <= 64

    def test_назначение_кнопок_не_изменилось(self) -> None:
        data = {b.callback_data for b in telegram_buttons(menu.main_menu(True))}
        assert data == {"menu:profile", "menu:security", "menu:history",
                        "menu:sessions", "menu:help"}

        data = {b.callback_data for b in telegram_buttons(menu.security_menu(True))}
        assert data == {"act:approval:off", "act:logout", "act:unlink:ask", "menu:home"}

        data = {b.callback_data for b in telegram_buttons(menu.security_menu(False))}
        assert "act:approval:on" in data

        data = {b.callback_data for b in telegram_buttons(menu.confirm_unlink())}
        assert data == {"act:unlink:yes", "menu:security"}

    def test_у_каждой_кнопки_один_значок_из_общего_набора(self) -> None:
        known = set(FALLBACK.values())
        for markup in self.every_markup():
            for button in telegram_buttons(markup):
                mark, _, text = button.text.partition(" ")
                assert mark in known, button.text
                assert text, button.text

    def test_возврат_и_отмена_помечены_одинаково(self) -> None:
        back = telegram_buttons(menu.back_only())[0]
        cancel = next(b for b in telegram_buttons(menu.confirm_unlink())
                      if b.text.endswith("Отмена"))

        assert back.text == f"{FALLBACK['back']} Назад"
        assert cancel.text == f"{FALLBACK['back']} Отмена"

    def test_опасное_действие_стоит_своей_строкой(self) -> None:
        rows = menu.security_menu(True).inline_keyboard
        unlink = [row for row in rows if row[0].callback_data == "act:unlink:ask"]

        assert len(unlink) == 1
        assert len(unlink[0]) == 1
        # И возврат — последней строкой, всегда один.
        assert [b.callback_data for b in rows[-1]] == ["menu:home"]

    def test_у_непривязанного_один_путь(self) -> None:
        markup = menu.main_menu(False)
        assert [b.callback_data for b in telegram_buttons(markup)] == ["menu:help"]


class TestФирменныеEmojiTelegram:
    def test_на_кнопке_остаётся_обычный_значок(self) -> None:
        # Надпись кнопки в Bot API — голый текст: сущностей к ней нет, и
        # показать custom emoji там нечем. Это ограничение платформы, а не
        # недоделка, и интерфейс обязан выглядеть целым без него.
        emoji = EmojiSet(telegram_raw=f"profile={TELEGRAM_ID}")
        markup = menu.main_menu(True, emoji=emoji)
        profile = next(b for b in telegram_buttons(markup)
                       if b.callback_data == "menu:profile")

        assert profile.text == f"{FALLBACK['profile']} Профиль"

    def test_в_тексте_сообщения_значок_фирменный(self) -> None:
        emoji = EmojiSet(telegram_raw=f"brand={TELEGRAM_ID}")
        assert emoji.telegram_html("brand") == (
            f'<tg-emoji emoji-id="{TELEGRAM_ID}">{FALLBACK["brand"]}</tg-emoji>'
        )

    def test_без_настройки_в_тексте_обычный_символ(self) -> None:
        assert DEFAULT.telegram_html("brand") == FALLBACK["brand"]

    def test_запасной_символ_можно_задать_свой(self) -> None:
        emoji = EmojiSet(telegram_raw=f"brand={TELEGRAM_ID}:🧱")
        assert emoji.telegram("brand") == "🧱"
        assert emoji.telegram_html("brand") == (
            f'<tg-emoji emoji-id="{TELEGRAM_ID}">🧱</tg-emoji>'
        )

    def test_откат_снимает_теги_но_оставляет_значки(self) -> None:
        # Право слать custom emoji есть не у каждого бота, и узнаётся это
        # только по отказу на отправку. Сообщение теряться от этого не должно.
        emoji = EmojiSet(telegram_raw=f"brand={TELEGRAM_ID}")
        text = f"{emoji.telegram_html('brand')} <b>KoFNetwork</b>"

        assert strip_custom_emoji(text) == f"{FALLBACK['brand']} <b>KoFNetwork</b>"

        plain = emoji.without_telegram_custom()
        assert not plain.has_telegram_custom
        assert plain.telegram_html("brand") == FALLBACK["brand"]

    def test_откат_не_трогает_исходный_набор(self) -> None:
        emoji = EmojiSet(telegram_raw=f"brand={TELEGRAM_ID}")
        emoji.without_telegram_custom()
        assert emoji.has_telegram_custom


# ---------------------------------------------------------------------- разбор


class TestРазборНастройки:
    @pytest.mark.parametrize("raw", [
        "",
        "профиль",                      # без «=»
        "profile=",                     # без значения
        "unknown_key=🧱",                # ключа нет в наборе
        f"profile=<:имя:{DISCORD_ID}>",  # имя не латиницей
        "profile=<:kof:12>",            # идентификатор короче любого настоящего
        "profile=kof_profile",          # похоже на имя, но идентификатора нет
    ])
    def test_мусор_не_ломает_интерфейс(self, raw: str) -> None:
        # Опечатка в переменной окружения не должна ронять бота: кнопка просто
        # остаётся со значком по умолчанию.
        emoji = EmojiSet(discord_raw=raw, telegram_raw=raw)
        assert emoji.discord("profile") == FALLBACK["profile"]
        assert emoji.telegram("profile") == FALLBACK["profile"]

    def test_несколько_записей_через_запятую_и_перевод_строки(self) -> None:
        emoji = EmojiSet(
            discord_raw=f" profile=<:kof_profile:{DISCORD_ID}>,\n"
                        f"security=<:kof_shield:{DISCORD_ID}> "
        )
        assert emoji.discord("profile").startswith("<:kof_profile:")
        assert emoji.discord("security").startswith("<:kof_shield:")

    def test_вместо_фирменного_можно_подставить_любой_символ(self) -> None:
        emoji = EmojiSet(discord_raw="history=🧭", telegram_raw="history=🧭")
        assert emoji.discord("history") == "🧭"
        assert emoji.telegram("history") == "🧭"

    def test_настройка_читается_из_окружения(self) -> None:
        settings = BrandSettings(emoji_discord=f"profile=<:kof_profile:{DISCORD_ID}>",
                                 emoji_telegram=f"profile={TELEGRAM_ID}")
        emoji = settings.emoji()

        assert emoji.discord("profile") == f"<:kof_profile:{DISCORD_ID}>"
        assert emoji.has_telegram_custom

    def test_пустая_настройка_это_рабочий_вид(self) -> None:
        emoji = BrandSettings().emoji()
        assert not emoji.has_telegram_custom
        for key, symbol in FALLBACK.items():
            assert emoji.discord(key) == symbol
            assert emoji.telegram(key) == symbol
