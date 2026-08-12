"""Служба карточек: кэш, поток отрисовки и отказоустойчивость.

Единственное, что нужно ботам, — это ``await service.image(card)``. Всё
остальное здесь: не рисовать дважды одно и то же, не рисовать в потоке
событий и не падать, если нарисовать не вышло.

Почему отдельный поток
----------------------

Отрисовка занимает десятки миллисекунд процессора. В цикле событий это
означает, что на всё это время бот перестаёт отвечать вообще всем: не только
тому, кто открыл профиль, но и тому, кто в этот момент подтверждает вход.
Подтверждение входа ждать не может — за ним стоит человек, которого не пускают
в игру. Поэтому рисование уходит в маленький пул потоков, а число потоков в
нём ограничено: без предела десять одновременных нажатий превратились бы в
десять параллельных отрисовок и съели бы весь процессор контейнера.

Почему одна отрисовка на всех
-----------------------------

Один и тот же экран часто просят одновременно: человек нажимает кнопку дважды,
Telegram присылает повтор, два игрока открывают справку. Рисовать это по
отдельности незачем — второй и последующие ждут первого по отпечатку
содержимого.

Почему тяжёлое подгружается по требованию
-----------------------------------------

Ни PIL, ни ресурсы не трогаются, пока не понадобится первая картинка. Так
отсутствие библиотеки рисования или каталога со шрифтами остаётся тем, чем
оно и является, — отсутствием оформления, а не отказом бота запускаться.
"""

from __future__ import annotations

import asyncio
import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import TYPE_CHECKING, Any, Final

from .cache import ByteCache, LruDict
from .model import Card
from .settings import CardSettings

if TYPE_CHECKING:  # pragma: no cover — только для проверки типов
    from .assets import Assets

LOGGER = logging.getLogger(__name__)

#: Сколько отпечатков картинок, уже загруженных в мессенджер, помнить. Значение
#: невесомое — строка идентификатора, — поэтому предел щедрый.
HANDLES: Final = 512

#: Имя файла баннера главного меню. Продублировано здесь, чтобы обращение к
#: нему не тянуло за собой модуль ресурсов вместе с PIL.
MENU_SCREEN: Final = "menu.jpg"


class CardService:
    """Готовые картинки экранов — с кэшем и без блокировки цикла событий."""

    __slots__ = ("_settings", "_assets", "_cache", "_handles", "_pool", "_pending",
                 "_renderer", "_disabled", "_lock")

    def __init__(self, settings: CardSettings | None = None,
                 assets: Assets | None = None) -> None:
        self._settings = settings or CardSettings()
        self._assets = assets
        self._cache = ByteCache(
            max_entries=self._settings.cache_entries,
            max_bytes=self._settings.cache_bytes,
            ttl=self._settings.cache_ttl,
        )
        #: Отпечаток → идентификатор уже загруженной картинки на стороне
        #: мессенджера. Telegram выдаёт ``file_id`` и принимает его вместо
        #: файла: повторная отправка того же экрана не грузит ничего.
        self._handles: LruDict[str, str] = LruDict(HANDLES)
        self._pool: ThreadPoolExecutor | None = None
        self._pending: dict[str, asyncio.Future[bytes | None]] = {}
        self._renderer: Any = None
        self._disabled = not self._settings.enabled
        #: Сборка рисовальщика идёт в потоке пула, и потоков там несколько.
        #: Без замка два первых запроса читали бы шрифт и шаблон одновременно.
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ состояние

    @property
    def enabled(self) -> bool:
        """Рисуются ли карточки вообще.

        Ответ может измениться на ходу: пока шрифт не понадобился, о его
        отсутствии никто не знает.
        """
        return not self._disabled

    @property
    def stats(self) -> dict[str, int]:
        return self._cache.stats

    def close(self) -> None:
        """Останавливает поток отрисовки. Вызывается при выключении бота."""
        if self._pool is not None:
            self._pool.shutdown(wait=False, cancel_futures=True)
            self._pool = None
        self._cache.clear()

    # ------------------------------------------------------------------ картинки

    async def image(self, card: Card) -> bytes | None:
        """Готовая карточка. ``None`` — значит показывать текстом.

        ``None`` возвращается и когда карточки выключены, и когда отрисовка
        отказала. Для вызывающего это одно и то же: у него в обоих случаях
        есть текст экрана, и им он и пользуется.
        """
        if self._disabled:
            return None

        key = card.digest()
        cached = self._cache.get(key)
        if cached is not None:
            return cached

        pending = self._pending.get(key)
        if pending is not None:
            # Тот же экран уже рисуется. Второй раз незачем: ждём первого.
            return await asyncio.shield(pending)

        loop = asyncio.get_running_loop()
        future: asyncio.Future[bytes | None] = loop.create_future()
        self._pending[key] = future
        data: bytes | None = None
        try:
            data = await loop.run_in_executor(self._executor(), self._draw, card)
        except Exception as exc:  # noqa: BLE001 — отрисовка не должна ронять бота
            LOGGER.warning("Карточка %s не нарисована: %s", card.kind, exc)
            data = None
        finally:
            # Результат объявляется даже при отмене: иначе те, кто ждёт этот
            # же экран, остались бы висеть на будущем, которое никто не
            # закроет, — и их сообщение не пришло бы вовсе.
            self._pending.pop(key, None)
            if data is not None:
                self._cache.put(key, data)
            if not future.done():
                future.set_result(data)
        return data

    def screen(self, name: str) -> bytes | None:
        """Заранее подготовленная картинка с диска.

        Чтение синхронное и это осознанно: файл читается один раз за всё время
        работы, дальше отдаётся из памяти. Уводить в поток то, что случается
        однажды, — лишний повод для гонки.
        """
        assets = self._resources()
        return None if assets is None else assets.screen(name)

    def menu(self) -> bytes | None:
        """Баннер главного меню."""
        return self.screen(MENU_SCREEN)

    async def help(self, card: Card) -> bytes | None:
        """Справка: сперва запечённая картинка, иначе — отрисовка.

        Порядок именно такой. Запечённая справка не стоит ничего, а отрисовка
        нужна ровно тогда, когда тексты команд поменяли, а картинку перепечь
        забыли: экран останется верным, просто соберётся сам.
        """
        if self._disabled:
            return None
        assets = self._resources()
        baked = None if assets is None else assets.screen_for(card)
        return baked if baked is not None else await self.image(card)

    # ------------------------------------------------------------------ выгрузка

    def handle(self, digest: str) -> str | None:
        """Идентификатор уже загруженной картинки, если он известен."""
        return self._handles.get(digest)

    def remember(self, digest: str, handle: str) -> None:
        """Запоминает идентификатор, выданный мессенджером."""
        if digest and handle:
            self._handles.put(digest, handle)

    # ------------------------------------------------------------------ внутреннее

    def _executor(self) -> ThreadPoolExecutor:
        if self._pool is None:
            self._pool = ThreadPoolExecutor(
                max_workers=max(1, self._settings.workers),
                thread_name_prefix="kofauth-cards",
            )
        return self._pool

    def _resources(self) -> Assets | None:
        """Каталог ресурсов. ``None`` — если рисовать нечем."""
        if self._disabled:
            return None
        if self._assets is None:
            try:
                from .assets import Assets as Resources
            except ImportError as exc:
                # Библиотеки рисования нет — оформления не будет, работа будет.
                LOGGER.warning("Карточки выключены: %s", exc)
                self._disabled = True
                return None
            self._assets = Resources(self._settings.directory)
        return self._assets

    def _draw(self, card: Card) -> bytes | None:
        """Отрисовка. Выполняется в потоке пула, не в цикле событий."""
        renderer = self._prepare()
        if renderer is None:
            return None
        return renderer.render(card)

    def _prepare(self) -> Any:
        """Готовит рисовальщик один раз на всё время работы."""
        if self._renderer is not None:
            return self._renderer
        with self._lock:
            if self._renderer is not None:
                return self._renderer
            assets = self._resources()
            if assets is None:
                return None
            fonts = assets.fonts()
            if fonts is None:
                # Шрифта нет — рисовать нечем, и пробовать снова на каждом
                # экране незачем: каталог посреди работы не появится.
                self._disabled = True
                return None
            from .render import Renderer

            self._renderer = Renderer(fonts, assets.template(),
                                      quality=self._settings.quality)
            return self._renderer
