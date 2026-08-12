"""Форматирование данных для экранов ботов."""

from __future__ import annotations

from kofauth_common import texts


class TestВремя:
    def test_iso_превращается_в_читаемое(self) -> None:
        assert texts.format_time("2026-08-04T12:30:00Z") == "04.08.2026 12:30 UTC"

    def test_пустое_значение_это_никогда(self) -> None:
        assert texts.format_time(None) == "никогда"

    def test_неразобранное_показывается_как_есть(self) -> None:
        # Показать сырую строку лучше, чем скрыть наличие данных за прочерком.
        assert texts.format_time("позавчера") == "позавчера"


class TestРасположение:
    def test_страна_и_город(self) -> None:
        assert texts.format_location("RU", "Москва") == "RU, Москва"

    def test_только_страна(self) -> None:
        assert texts.format_location("RU", None) == "RU"

    def test_ничего_неизвестно(self) -> None:
        assert texts.format_location(None, None) == "неизвестно"


class TestВторойФактор:
    def test_totp_показывается_приложением(self) -> None:
        assert texts.format_two_factor({"totpEnabled": True}) == "приложение-аутентификатор"

    def test_методы_переводятся(self) -> None:
        result = texts.format_two_factor({"twoFactor": ["TELEGRAM", "DISCORD"]})
        assert result == "Telegram, Discord"

    def test_без_методов_выключен(self) -> None:
        assert texts.format_two_factor({"twoFactor": []}) == "выключен"


class TestСписки:
    def test_пустая_история_объясняется_словами(self) -> None:
        # Пустой экран без текста читается как поломка.
        assert texts.history_lines([]) == ["История пуста."]

    def test_неудачный_вход_помечен(self) -> None:
        lines = texts.history_lines([
            {"at": "2026-08-04T10:00:00Z", "success": False, "ip": "1.2.*.*",
             "result": "BAD_PASSWORD"}
        ])
        assert "❌" in lines[0]
        assert "BAD_PASSWORD" in lines[0]


class TestПрофиль:
    PROFILE = {
        "username": "Steve",
        "uuid": "550e8400-e29b-41d4-a716-446655440000",
        "status": "ACTIVE",
        "registeredAt": "2026-08-01T10:00:00Z",
        "lastLoginAt": "2026-08-04T10:00:00Z",
        "lastLoginIp": "1.2.3.***",
        "country": "RU",
        "city": "Москва",
    }

    def test_показывает_ник_и_даты(self) -> None:
        lines = texts.profile_lines(self.PROFILE)
        assert any("Steve" in line for line in lines)
        assert any(line.startswith("Регистрация:") for line in lines)
        assert any(line.startswith("Последний вход:") for line in lines)

    def test_не_показывает_служебное_и_личное(self) -> None:
        # UUID и статус игроку ничего не говорят, а адрес и город — данные,
        # из-за которых экран профиля нельзя показать другу.
        body = "\n".join(texts.profile_lines(self.PROFILE))
        assert "550e8400" not in body
        assert "ACTIVE" not in body
        assert "1.2.3" not in body
        assert "Москва" not in body

    def test_донат_появляется_только_с_адресом(self) -> None:
        assert not any(
            "Поддержать" in line for line in texts.profile_lines(self.PROFILE)
        )
        with_donate = texts.profile_lines(self.PROFILE, donate_url="https://kof.net/donate")
        assert any("https://kof.net/donate" in line for line in with_donate)

    def test_временная_блокировка_видна(self) -> None:
        lines = texts.profile_lines({
            "username": "Steve", "temporarilyLocked": True,
            "lockedUntil": "2026-08-04T13:00:00Z",
        })
        assert any("блокировка" in line.lower() for line in lines)


class TestОшибки:
    def test_известный_код_объясняется(self) -> None:
        assert "истёк" in texts.describe_error("CODE_INVALID")

    def test_неизвестный_код_не_показывает_внутренности(self) -> None:
        # Код вида SQLSTATE в чате игрока бесполезен и выглядит как поломка.
        assert texts.describe_error("SOME_WEIRD_CODE") == texts.INTERNAL_ERROR
