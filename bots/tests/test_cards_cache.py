"""Кэш карточек и служба отрисовки.

Кэш здесь не про скорость, а про пределы. Готовая карточка весит сотни
килобайт, и кэш «на каждого игрока» — это утечка с отсрочкой: она незаметна на
десяти людях и валит бота на десяти тысячах. Поэтому проверяется не то, что
попадание случается, а то, что записи вытесняются: по числу, по объёму и по
времени.

Служба проверяется с тем же прицелом: повторный запрос неизменившегося экрана
не должен рисовать заново, одновременные запросы одного экрана — рисовать
дважды, а любой отказ отрисовки обязан превращаться в ``None``, то есть в
текстовый экран, а не в исключение посреди обработчика.
"""

from __future__ import annotations

import asyncio

import pytest
from kofauth_common.cards import model
from kofauth_common.cards.cache import ByteCache, LruDict
from kofauth_common.cards.service import CardService
from kofauth_common.cards.settings import CardSettings

PROFILE = {"username": "Inroka", "registeredAt": "2026-02-01T09:14:00Z"}


class Clock:
    """Часы, которыми управляет тест: ждать секунды по-настоящему незачем."""

    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def tick(self, seconds: float) -> None:
        self.now += seconds


class TestКэш:
    def test_кладётся_и_достаётся(self) -> None:
        cache = ByteCache()
        cache.put("ключ", b"card")
        assert cache.get("ключ") == b"card"

    def test_чужого_ключа_нет(self) -> None:
        assert ByteCache().get("нет") is None

    def test_просроченное_не_отдаётся(self) -> None:
        # Данные на карточке устаревают молча: никто не сообщит боту, что
        # игрок зашёл в игру.
        clock = Clock()
        cache = ByteCache(ttl=60, clock=clock)
        cache.put("ключ", b"card")
        clock.tick(61)
        assert cache.get("ключ") is None
        assert len(cache) == 0

    def test_свежее_переживает_обращение(self) -> None:
        clock = Clock()
        cache = ByteCache(ttl=60, clock=clock)
        cache.put("ключ", b"card")
        clock.tick(59)
        assert cache.get("ключ") == b"card"

    def test_число_записей_ограничено(self) -> None:
        cache = ByteCache(max_entries=3)
        for index in range(10):
            cache.put(f"ключ-{index}", b"x" * 100)
        assert len(cache) == 3
        assert cache.get("ключ-0") is None
        assert cache.get("ключ-9") is not None

    def test_объём_ограничен(self) -> None:
        # Считать надо байты, а не записи: сто карточек по четверти мегабайта
        # это двадцать пять мегабайт.
        cache = ByteCache(max_entries=100, max_bytes=1000)
        for index in range(10):
            cache.put(f"ключ-{index}", b"x" * 300)
        assert cache.volume <= 1000
        assert len(cache) == 3

    def test_вытесняется_самое_давнее(self) -> None:
        cache = ByteCache(max_entries=2)
        cache.put("первый", b"1")
        cache.put("второй", b"2")
        cache.get("первый")  # обращение делает его свежим
        cache.put("третий", b"3")
        assert cache.get("первый") is not None
        assert cache.get("второй") is None

    def test_слишком_большое_не_кладётся(self) -> None:
        # Одна запись больше всего кэша вытеснила бы вообще всё.
        cache = ByteCache(max_bytes=100)
        cache.put("прежний", b"x" * 50)
        cache.put("огромный", b"x" * 500)
        assert cache.get("огромный") is None
        assert cache.get("прежний") is not None

    def test_повторная_запись_не_удваивает_объём(self) -> None:
        cache = ByteCache()
        cache.put("ключ", b"x" * 100)
        cache.put("ключ", b"y" * 100)
        assert cache.volume == 100
        assert len(cache) == 1

    def test_счётчики_ведутся(self) -> None:
        cache = ByteCache(max_entries=1)
        cache.put("первый", b"1")
        cache.get("первый")
        cache.get("нет")
        cache.put("второй", b"2")
        assert cache.stats["hits"] == 1
        assert cache.stats["misses"] == 1
        assert cache.stats["evicted"] == 1


class TestСловарьИдентификаторов:
    def test_помнит_и_вытесняет(self) -> None:
        handles: LruDict[str, str] = LruDict(limit=2)
        handles.put("a", "file-1")
        handles.put("b", "file-2")
        handles.put("c", "file-3")
        assert handles.get("a") is None
        assert handles.get("c") == "file-3"
        assert len(handles) == 2


class Counter:
    """Рисовальщик-обманка: считает вызовы вместо того, чтобы рисовать."""

    def __init__(self, data: bytes = b"jpeg", delay: float = 0.0) -> None:
        self.calls = 0
        self._data = data
        self._delay = delay

    def render(self, card) -> bytes:
        self.calls += 1
        if self._delay:
            import time

            time.sleep(self._delay)
        return self._data


def service(renderer: object | None = None, **options) -> CardService:
    """Служба с подменённым рисовальщиком: ресурсы для этих проверок не нужны."""
    result = CardService(CardSettings(**options))
    if renderer is not None:
        result._renderer = renderer  # noqa: SLF001 — подмена ради счётчика
    return result


class TestСлужба:
    async def test_рисует_и_отдаёт(self) -> None:
        counter = Counter()
        cards = service(counter)
        assert await cards.image(model.profile_card(PROFILE)) == b"jpeg"
        assert counter.calls == 1
        cards.close()

    async def test_неизменившееся_не_рисуется_заново(self) -> None:
        counter = Counter()
        cards = service(counter)
        await cards.image(model.profile_card(PROFILE))
        await cards.image(model.profile_card(PROFILE))
        assert counter.calls == 1
        cards.close()

    async def test_изменившееся_рисуется(self) -> None:
        counter = Counter()
        cards = service(counter)
        await cards.image(model.profile_card(PROFILE))
        await cards.image(model.profile_card(dict(PROFILE, username="Steve")))
        assert counter.calls == 2
        cards.close()

    async def test_одновременные_запросы_рисуют_один_раз(self) -> None:
        # Человек нажимает кнопку дважды, Telegram присылает повтор, два
        # игрока открывают справку — рисовать это по отдельности незачем.
        counter = Counter(delay=0.05)
        cards = service(counter)
        card = model.profile_card(PROFILE)
        results = await asyncio.gather(*(cards.image(card) for _ in range(5)))
        assert results == [b"jpeg"] * 5
        assert counter.calls == 1
        cards.close()

    async def test_выключенные_карточки_молчат(self) -> None:
        cards = service(Counter(), enabled=False)
        assert await cards.image(model.profile_card(PROFILE)) is None
        assert cards.enabled is False
        cards.close()

    async def test_отказ_отрисовки_это_текстовый_экран(self) -> None:
        # Отказ обязан превращаться в None, а не в исключение посреди
        # обработчика: у бота есть текст экрана, и он им воспользуется.
        class Broken:
            def render(self, card):
                raise RuntimeError("шрифт испорчен")

        cards = service(Broken())
        assert await cards.image(model.profile_card(PROFILE)) is None
        cards.close()

    async def test_отказ_не_запоминается_как_результат(self) -> None:
        class Flaky:
            def __init__(self) -> None:
                self.calls = 0

            def render(self, card):
                self.calls += 1
                if self.calls == 1:
                    raise RuntimeError("не в этот раз")
                return b"jpeg"

        flaky = Flaky()
        cards = service(flaky)
        card = model.profile_card(PROFILE)
        assert await cards.image(card) is None
        assert await cards.image(card) == b"jpeg"
        cards.close()

    async def test_кэш_ограничен_настройками(self) -> None:
        counter = Counter()
        cards = service(counter, cache_entries=2)
        for name in ("A", "B", "C"):
            await cards.image(model.profile_card(dict(PROFILE, username=name)))
        # Первый вытеснен — значит, будет нарисован заново.
        await cards.image(model.profile_card(dict(PROFILE, username="A")))
        assert counter.calls == 4
        cards.close()

    async def test_срок_жизни_соблюдается(self) -> None:
        counter = Counter()
        cards = service(counter, cache_ttl=1)
        card = model.profile_card(PROFILE)
        await cards.image(card)
        # Часы переводятся заведомо далеко вперёд: monotonic на разных
        # системах стартует с разного значения, и «плюс десять тысяч» местами
        # оказалось бы в прошлом.
        cards._cache._clock = lambda: 1e12  # noqa: SLF001 — перевод часов
        await cards.image(card)
        assert counter.calls == 2
        cards.close()

    def test_идентификаторы_запоминаются(self) -> None:
        cards = service()
        cards.remember("отпечаток", "file-1")
        assert cards.handle("отпечаток") == "file-1"
        assert cards.handle("другой") is None
        cards.close()

    def test_пустой_идентификатор_не_запоминается(self) -> None:
        cards = service()
        cards.remember("отпечаток", "")
        assert cards.handle("отпечаток") is None
        cards.close()

    async def test_нет_ресурсов_нет_картинки(self, tmp_path) -> None:
        # Каталог без шрифтов: бот обязан продолжить работу текстом.
        cards = CardService(CardSettings(directory=str(tmp_path)))
        assert await cards.image(model.profile_card(PROFILE)) is None
        assert cards.enabled is False
        cards.close()

    async def test_справка_берётся_готовой(self) -> None:
        # Запечённая справка не стоит ничего, отрисовка нужна лишь тогда,
        # когда картинку перепечь забыли.
        counter = Counter()
        cards = service(counter)
        assert await cards.help(model.help_card("TELEGRAM")) is not None
        assert counter.calls == 0
        cards.close()

    async def test_незапечённая_справка_рисуется(self) -> None:
        counter = Counter()
        cards = service(counter)
        assert await cards.help(model.help_card("MATRIX")) == b"jpeg"
        assert counter.calls == 1
        cards.close()


@pytest.fixture(autouse=True)
def _no_stray_threads() -> None:
    """Каждый тест закрывает свою службу сам; фикстура ловит забытые."""
    yield
