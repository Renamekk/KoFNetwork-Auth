"""Содержимое графических экранов.

Проверяется не красота, а то, что её нельзя сломать незаметно: что на карточку
попали настоящие данные аккаунта, что маскировка адреса не потерялась по
дороге, что пустое значение стало прочерком, а не словом «None», и что число
записей задаёт политика показа, а не размер картинки.

Сама картинка проверяется отдельно (``test_cards_render``). Здесь — только
содержимое: ровно то, что можно прочитать, не открывая ни одного файла.
"""

from __future__ import annotations

from kofauth_common import texts
from kofauth_common.cards import model, theme

PROFILE = {
    "username": "Inroka",
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "status": "ACTIVE",
    "registeredAt": "2026-02-01T09:14:00Z",
    "lastLoginAt": "2026-08-12T15:10:00Z",
    "lastLoginIp": "127.0.0.***",
    "twoFactor": ["TELEGRAM", "DISCORD"],
    "totpEnabled": False,
    "loginApproval": True,
    "notifications": True,
    "captchaPassed": True,
}

HISTORY = [
    {"at": "2026-08-12T15:10:00Z", "success": True, "ip": "127.0.0.***"},
    {"at": "2026-08-11T21:42:00Z", "success": False, "ip": "185.12.4.***",
     "result": "BAD_PASSWORD"},
]


def values(card: model.Card) -> list[str]:
    """Всё, что на карточке написано, — одним списком."""
    result: list[str] = [card.title, card.footer]
    for row in card.fields:
        result.extend([row.label, row.value])
    for entry in card.entries:
        result.extend([column.text for column in entry.columns])
        result.append(entry.sub)
    for section in card.sections:
        result.append(section.heading)
        result.extend(section.lines)
    return result


class TestПрофиль:
    def test_подставляет_ник_и_даты(self) -> None:
        card = model.profile_card(PROFILE, platform="Telegram")
        assert ("Ник", "Inroka") in [(f.label, f.value) for f in card.fields]
        assert "01.02.2026" in values(card)
        assert "12.08.2026 15:10 UTC" in values(card)

    def test_дата_регистрации_без_времени(self) -> None:
        # Дату регистрации человек сверяет с памятью, а не с часами: время
        # в ней только занимает место, которого на карточке нет.
        card = model.profile_card(PROFILE)
        registered = next(f for f in card.fields if f.label == "Регистрация")
        assert registered.value == "01.02.2026"

    def test_привязанные_площадки_видны(self) -> None:
        card = model.profile_card(PROFILE)
        factor = next(f for f in card.fields if f.label == "Второй фактор")
        assert "Telegram" in factor.value
        assert "Discord" in factor.value

    def test_площадка_открытия_в_подвале(self) -> None:
        # Единственное утверждение о привязке, которое можно сделать честно:
        # экран открыт отсюда, значит, эта площадка привязана.
        assert model.profile_card(PROFILE, platform="Discord").footer == "Привязано: Discord"

    def test_пустые_значения_становятся_прочерком(self) -> None:
        card = model.profile_card({})
        assert [f.value for f in card.fields] == ["—", "неизвестно", "никогда", "выключен"]

    def test_блокировка_добавляет_строку_и_попадает_в_текст(self) -> None:
        # Временная блокировка — причина, по которой человек прямо сейчас не
        # может войти. Такое не прячут внутрь картинки.
        card = model.profile_card(dict(PROFILE, temporarilyLocked=True,
                                       lockedUntil="2026-08-12T20:00:00Z"))
        assert card.fields[-1].label == "Блокировка до"
        assert card.fields[-1].tone == theme.TONE_WARN
        assert any("заблокирован" in line for line in card.caption)

    def test_служебные_поля_на_карточку_не_попадают(self) -> None:
        # UUID и статус игроку ничего не говорят, а картинку пересылают.
        written = " ".join(values(model.profile_card(PROFILE, platform="Telegram")))
        assert "550e8400" not in written
        assert "ACTIVE" not in written

    def test_поддержка_сервера_остаётся_ссылкой_в_подписи(self) -> None:
        # Ссылку с картинки пришлось бы переписывать руками.
        card = model.profile_card(PROFILE, donate_url="https://kof.example/donate")
        assert "https://kof.example/donate" not in " ".join(values(card))
        assert any("kof.example" in line for line in card.caption)

    def test_запасной_текст_совпадает_с_прежним_экраном(self) -> None:
        assert list(model.profile_card(PROFILE).text) == texts.profile_lines(PROFILE)


class TestЗащита:
    def test_состояния_показаны_словом_и_цветом(self) -> None:
        card = model.security_card(PROFILE)
        approval = card.fields[0]
        assert (approval.value, approval.tone) == ("ВКЛ", theme.TONE_OK)

    def test_выключенное_помечено_красным(self) -> None:
        card = model.security_card(dict(PROFILE, loginApproval=False))
        assert (card.fields[0].value, card.fields[0].tone) == ("ВЫКЛ", theme.TONE_DANGER)

    def test_уровень_защиты_считается_по_двум_признакам(self) -> None:
        assert model.security_level(PROFILE) == ("высокий", theme.TONE_OK)
        assert model.security_level(dict(PROFILE, twoFactor=[]))[0] == "средний"
        assert model.security_level({})[0] == "базовый"

    def test_слабая_защита_объясняется_текстом(self) -> None:
        # Предупреждение, набранное внутри картинки, нельзя ни процитировать,
        # ни прочитать голосом.
        card = model.security_card({})
        assert card.caption
        assert "Уровень защиты" in card.caption[0]

    def test_при_высокой_защите_предупреждения_нет(self) -> None:
        assert model.security_card(PROFILE).caption == ()


class TestИсторияВходов:
    def test_маскировка_адреса_сохраняется(self) -> None:
        # Адрес приходит замаскированным с сервера и здесь не трогается:
        # карточку пересылают одним нажатием.
        card = model.history_card(HISTORY)
        assert "127.0.0.***" in values(card)
        assert "185.12.4.***" in values(card)

    def test_неудачный_вход_отличим(self) -> None:
        card = model.history_card(HISTORY)
        assert [entry.icon for entry in card.entries] == ["check", "cross"]
        assert card.entries[1].sub == "неверный пароль"

    def test_показывается_не_больше_политики(self) -> None:
        card = model.history_card([HISTORY[0]] * 25)
        assert len(card.entries) == texts.HISTORY_LIMIT

    def test_про_скрытые_записи_сказано_словами(self) -> None:
        card = model.history_card([HISTORY[0]] * 25)
        assert "10 из 25" in card.footer
        assert card.caption and "25" in card.caption[0]

    def test_пустая_история_объясняется(self) -> None:
        # Пустая панель читается как сбой отрисовки, а не как «записей нет».
        card = model.history_card([])
        assert card.entries == ()
        assert card.empty == "Входов пока не было"
        assert card.footer == ""

    def test_дата_и_время_разведены_по_столбцам(self) -> None:
        columns = model.history_card(HISTORY).entries[0].columns
        assert [c.text for c in columns] == ["12.08.2026", "15:10", "127.0.0.***"]


class TestСессии:
    SESSIONS = [
        {"type": "GAME", "ip": "127.0.0.***", "server": "survival",
         "lastSeenAt": "2026-08-12T15:10:00Z"},
        {"type": "WEB", "ip": "185.12.4.***", "server": None,
         "lastSeenAt": "2026-08-12T14:02:00Z"},
    ]

    def test_показывает_тип_и_адрес(self) -> None:
        card = model.sessions_card(self.SESSIONS)
        assert [c.text for c in card.entries[0].columns] == ["GAME", "127.0.0.***"]

    def test_сессия_вне_сервера_названа_словами(self) -> None:
        assert "вне сервера" in model.sessions_card(self.SESSIONS).entries[1].sub

    def test_пусто_объясняется(self) -> None:
        assert model.sessions_card([]).empty == "Активных сессий нет"


class TestСправка:
    def test_у_каждой_площадки_свои_команды(self) -> None:
        telegram = " ".join(values(model.help_card("TELEGRAM")))
        discord = " ".join(values(model.help_card("DISCORD")))
        assert "/telegram" in telegram and "/discord" not in telegram
        assert "/discord" in discord and "/telegram" not in discord

    def test_направление_привязки_вынесено_в_подвал(self) -> None:
        assert model.help_card("TELEGRAM").footer == "Код выдаётся только в игре"

    def test_ссылок_на_карточке_нет(self) -> None:
        # Карточка лежит в репозитории и адреса конкретной сети не знает.
        assert "http" not in " ".join(values(model.help_card("DISCORD")))

    def test_неизвестная_площадка_не_роняет(self) -> None:
        assert model.help_card("MATRIX").sections == ()


class TestОтпечаток:
    def test_одинаковые_данные_дают_одинаковый_отпечаток(self) -> None:
        assert model.profile_card(PROFILE).digest() == model.profile_card(PROFILE).digest()

    def test_другой_ник_другой_отпечаток(self) -> None:
        other = dict(PROFILE, username="Steve")
        assert model.profile_card(PROFILE).digest() != model.profile_card(other).digest()

    def test_разные_экраны_не_совпадают(self) -> None:
        assert model.profile_card(PROFILE).digest() != model.security_card(PROFILE).digest()

    def test_версия_оформления_обесценивает_кэш(self, monkeypatch) -> None:
        # После правки палитры прежние картинки обязаны перестать считаться
        # подходящими: иначе обновление доходит до людей с задержкой в срок
        # жизни кэша.
        before = model.profile_card(PROFILE).digest()
        monkeypatch.setattr(theme, "LAYOUT_VERSION", "2")
        assert model.profile_card(PROFILE).digest() != before

    def test_подпись_в_отпечаток_не_входит(self) -> None:
        # Подпись не нарисована на картинке, и её правка не должна выбрасывать
        # уже нарисованное.
        plain = model.profile_card(PROFILE)
        with_donate = model.profile_card(PROFILE, donate_url="https://kof.example")
        assert plain.digest() == with_donate.digest()
