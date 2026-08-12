"""Кэш готовых карточек.

Карточка стоит десятки миллисекунд процессорного времени и сотни килобайт
памяти, а меняется редко: между двумя нажатиями «Профиль» у человека обычно не
меняется ничего. Поэтому готовый JPEG запоминается под отпечатком содержимого
(:meth:`..model.Card.digest`) — и при неизменившихся данных не рисуется заново.

Границы у кэша три, и каждая нужна.

* **По числу записей** — чтобы словарь не рос вместе с числом игроков. Кэш на
  каждого пользователя — это утечка с отсрочкой: она незаметна на десяти
  людях и валит бота на десяти тысячах.
* **По объёму** — потому что запись это не ключ, а картинка: сто записей по
  четверти мегабайта это двадцать пять мегабайт, и считать надо байты.
* **По времени** — потому что данные на карточке устаревают молча. Никто не
  сообщит боту, что игрок зашёл в игру; истёкший срок жизни — единственный
  способ узнать об этом.

Файлов кэш не создаёт. Каталог с картинками на каждого пользователя пришлось
бы чистить, переносить между контейнерами и не забыть исключить из образа —
и он всё равно хранил бы персональные данные дольше, чем нужно.
"""

from __future__ import annotations

import time
from collections import OrderedDict
from collections.abc import Callable
from typing import Generic, TypeVar

K = TypeVar("K")
V = TypeVar("V")


class LruDict(Generic[K, V]):
    """Словарь с пределом по числу записей.

    Нужен там, где значение ничего не весит, но ключей может быть сколько
    угодно: например, выданные Telegram идентификаторы уже загруженных
    картинок.
    """

    __slots__ = ("_items", "_limit")

    def __init__(self, limit: int = 256) -> None:
        self._items: OrderedDict[K, V] = OrderedDict()
        self._limit = max(1, limit)

    def get(self, key: K) -> V | None:
        value = self._items.get(key)
        if value is not None:
            self._items.move_to_end(key)
        return value

    def put(self, key: K, value: V) -> None:
        self._items[key] = value
        self._items.move_to_end(key)
        while len(self._items) > self._limit:
            self._items.popitem(last=False)

    def clear(self) -> None:
        self._items.clear()

    def __len__(self) -> int:
        return len(self._items)

    def __contains__(self, key: object) -> bool:
        return key in self._items


class ByteCache:
    """Кэш картинок: по числу записей, по объёму и по сроку жизни.

    :param clock: источник времени. Подменяется в тестах — иначе проверка
        срока жизни означала бы ожидание в реальных секундах.
    """

    __slots__ = ("_items", "_limit", "_bytes_limit", "_ttl", "_clock", "_bytes",
                 "_hits", "_misses", "_evicted")

    def __init__(self, max_entries: int = 48, max_bytes: int = 24 * 1024 * 1024,
                 ttl: float = 600.0,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._items: OrderedDict[str, tuple[float, bytes]] = OrderedDict()
        self._limit = max(1, max_entries)
        self._bytes_limit = max(1, max_bytes)
        self._ttl = max(1.0, ttl)
        self._clock = clock
        self._bytes = 0
        self._hits = 0
        self._misses = 0
        self._evicted = 0

    # ------------------------------------------------------------------ доступ

    def get(self, key: str) -> bytes | None:
        found = self._items.get(key)
        if found is None:
            self._misses += 1
            return None
        stored, value = found
        if self._clock() - stored > self._ttl:
            # Просроченное удаляется при обращении, а не по расписанию: таймер
            # ради чистки кэша — это ещё одна задача, которая может упасть.
            self._drop(key)
            self._misses += 1
            return None
        self._items.move_to_end(key)
        self._hits += 1
        return value

    def put(self, key: str, value: bytes) -> None:
        if len(value) > self._bytes_limit:
            # Одна запись больше всего кэша вытеснила бы вообще всё. Такую
            # проще не хранить: она всё равно не доживёт до второго обращения.
            return
        if key in self._items:
            self._drop(key)
        self._items[key] = (self._clock(), value)
        self._bytes += len(value)
        self._squeeze()

    def _squeeze(self) -> None:
        """Вытесняет самое давнее, пока кэш не влезет в оба предела."""
        while self._items and (len(self._items) > self._limit
                               or self._bytes > self._bytes_limit):
            _, (_, value) = self._items.popitem(last=False)
            self._bytes -= len(value)
            self._evicted += 1

    def _drop(self, key: str) -> None:
        found = self._items.pop(key, None)
        if found is not None:
            self._bytes -= len(found[1])

    def clear(self) -> None:
        self._items.clear()
        self._bytes = 0

    # ------------------------------------------------------------------ счёт

    def __len__(self) -> int:
        return len(self._items)

    @property
    def volume(self) -> int:
        """Сколько байт сейчас занято."""
        return self._bytes

    @property
    def stats(self) -> dict[str, int]:
        """Попадания, промахи и вытеснения — для журнала при выключении."""
        return {"hits": self._hits, "misses": self._misses,
                "evicted": self._evicted, "entries": len(self._items),
                "bytes": self._bytes}
