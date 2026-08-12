"""Содержимое карточек: что на них написано.

Модуль ничего не рисует. Он превращает ответ ``/api/bot`` в набор строк с
пометками о том, какой строке какой оттенок и какой значок положен, — и на
этом останавливается. Дальше :mod:`.render` кладёт краску, а :mod:`.cache`
опознаёт содержимое по отпечатку.

Разделение не формальное. Содержимое карточки — это то, что можно проверить:
что маскировка адреса не потерялась, что пустое значение не превратилось в
слово «None», что длинный ник не выкинул из строки всё остальное. Проверять
это по картинке нечем.

Что показывается, а что нет
---------------------------

Правило то же, что и у текстовых экранов в :mod:`..texts`: на карточку идёт
только то, что человек хочет знать о себе сам. UUID, идентификаторы запросов,
коды привязки и полные адреса не идут **никогда** — картинку пересылают, ею
хвастаются в чате, и всё, что на ней есть, рано или поздно оказывается на
чужом экране. Адреса приходят от сервера уже замаскированными; здесь они не
восстанавливаются и не дополняются.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from hashlib import blake2b
from typing import Any

from .. import texts
from . import theme

#: Виды карточек. Строка, а не перечисление: вид входит в отпечаток и в имя
#: файла вложения, и в обоих местах нужна именно строка.
PROFILE = "profile"
SECURITY = "security"
HISTORY = "history"
SESSIONS = "sessions"
HELP = "help"

#: Разделитель значений в одной строке. Тонкий, как в макете.
SEPARATOR = " · "

#: Чем заменяется отсутствующее значение. Прочерк, а не пустое место: пустое
#: место читается как «не нарисовалось», прочерк — как «нет данных».
NOTHING = "—"


@dataclass(frozen=True, slots=True)
class Field:
    """Строка вида «Подпись: значение».

    Значение отделено от подписи не только цветом, но и типом: подпись
    постоянна, значение приходит извне и потому единственное, что нуждается в
    подгонке по ширине.
    """

    icon: str
    label: str
    value: str
    tone: str = theme.TONE_ACCENT

    def parts(self) -> tuple[str, ...]:
        return (self.icon, self.label, self.value, self.tone)


@dataclass(frozen=True, slots=True)
class Column:
    """Ячейка табличной строки."""

    text: str
    tone: str = theme.TONE_TEXT

    def parts(self) -> tuple[str, ...]:
        return (self.text, self.tone)


@dataclass(frozen=True, slots=True)
class Entry:
    """Строка списка: значок, несколько колонок и необязательная приписка.

    Приписка мелкая и приглушённая — в ней то, что нужно не всегда: причина
    неудачного входа, сервер сессии. Выносить это в колонки значило бы
    растянуть таблицу под самый редкий случай.
    """

    icon: str
    columns: tuple[Column, ...]
    sub: str = ""

    def parts(self) -> tuple[str, ...]:
        return (self.icon, self.sub, *(part for c in self.columns for part in c.parts()))


@dataclass(frozen=True, slots=True)
class Section:
    """Раздел справки: значок, заголовок и несколько строк текста."""

    icon: str
    heading: str
    lines: tuple[str, ...]

    def parts(self) -> tuple[str, ...]:
        return (self.icon, self.heading, *self.lines)


@dataclass(frozen=True, slots=True)
class Card:
    """Готовое содержимое одного экрана.

    :param caption: подпись к картинке. Короткая: данные видны на самой
        карточке, а в подписи остаётся то, что нельзя прятать в изображение —
        предупреждения, ссылки и оговорки вроде «показаны не все записи».
    :param text: полный текст того же экрана. Им бот заменяет карточку, если
        нарисовать или отправить её не вышло. Это не запасной вариант «на
        всякий случай», а обязательное условие: интерфейс не должен зависеть
        от того, удалось ли собрать картинку.
    """

    kind: str
    title: str
    fields: tuple[Field, ...] = ()
    entries: tuple[Entry, ...] = ()
    sections: tuple[Section, ...] = ()
    footer: str = ""
    footer_tone: str = theme.TONE_MUTED
    #: Что написать посреди панели, когда показывать нечего. Пустая панель
    #: читается как сбой отрисовки, а не как «записей нет», и человек идёт
    #: жаловаться на сломанного бота вместо того, чтобы понять ответ.
    empty: str = "Пока пусто"
    caption: tuple[str, ...] = ()
    text: tuple[str, ...] = field(default=(), compare=False)

    def digest(self) -> str:
        """Отпечаток нарисованного содержимого.

        По нему кэш узнаёт, что карточку с такими данными уже рисовали. В
        отпечаток входит и версия оформления: после правки палитры прежние
        картинки обязаны перестать считаться подходящими, иначе обновление
        бота доходило бы до людей с задержкой в срок жизни кэша.

        Подпись и запасной текст в отпечаток не входят: они не нарисованы на
        картинке, и их правка не должна выбрасывать кэш.
        """
        stamp = blake2b(digest_size=12)
        stamp.update(theme.LAYOUT_VERSION.encode())
        for chunk in (self.kind, self.title, self.footer, self.footer_tone, self.empty):
            stamp.update(b"\x1f")
            stamp.update(chunk.encode("utf-8", "replace"))
        for item in (*self.fields, *self.entries, *self.sections):
            for part in item.parts():
                stamp.update(b"\x1e")
                stamp.update(part.encode("utf-8", "replace"))
        return stamp.hexdigest()


# ------------------------------------------------------------------ значения


def _text(value: Any, empty: str = NOTHING) -> str:
    """Значение из ответа API в строку.

    ``None`` и пустая строка — одно и то же «нет данных»: у одного поля сервер
    присылает ``null``, у другого пустую строку, и разница между ними человеку
    ничего не говорит.
    """
    if value is None:
        return empty
    result = str(value).strip()
    return result or empty


def _switch(value: Any) -> tuple[str, str]:
    """Состояние переключателя: слово и его оттенок.

    Зелёный и красный здесь единственные во всей карточке. «ВКЛ» и «ВЫКЛ»
    различаются одним слогом, и на беглом взгляде их разводит только цвет.
    """
    return ("ВКЛ", theme.TONE_OK) if value else ("ВЫКЛ", theme.TONE_DANGER)


def messengers(profile: dict[str, Any]) -> str:
    """Мессенджеры, подтверждающие вход.

    Берётся из ``twoFactor``: в KoFAuth Telegram и Discord попадают туда ровно
    тогда, когда у привязки включено подтверждение входа. Поэтому строка
    отвечает на вопрос «чем защищён вход», а не «что привязано» — привязка без
    подтверждения ничего не подтверждает, и показывать её как защиту было бы
    обманом.
    """
    names = {"TELEGRAM": "Telegram", "DISCORD": "Discord"}
    found = [names[str(m)] for m in (profile.get("twoFactor") or []) if str(m) in names]
    return SEPARATOR.join(found)


def second_factor(profile: dict[str, Any]) -> str:
    """Второй фактор одной строкой — тем же составом, что и в тексте."""
    return texts.format_two_factor(profile)


def security_level(profile: dict[str, Any]) -> tuple[str, str]:
    """Оценка защиты аккаунта: слово и оттенок.

    Считается по двум признакам, а не по четырём: подтверждение входа кнопкой
    и второй фактор — единственное, что действительно мешает войти с чужим
    паролем. Уведомления сообщают о случившемся, а CAPTCHA защищает форму, а
    не аккаунт, и складывать их в один балл значило бы обещать защиту, которой
    нет.
    """
    approval = bool(profile.get("loginApproval"))
    factor = bool(profile.get("totpEnabled")) or bool(profile.get("twoFactor"))
    if approval and factor:
        return "высокий", theme.TONE_OK
    if approval or factor:
        return "средний", theme.TONE_WARN
    return "базовый", theme.TONE_DANGER


# ------------------------------------------------------------------- экраны


def profile_card(profile: dict[str, Any], donate_url: str = "",
                 platform: str = "") -> Card:
    """Профиль: кто ты, с какого числа и чем защищён вход.

    Четыре строки — столько же, сколько в макете. Пятая появляется только при
    временной блокировке: это состояние, а не свойство аккаунта, и в спокойном
    виде экрана ему места нет.

    :param platform: человеческое имя площадки, из которой открыт экран.
        Она привязана заведомо — иначе профиль не нашёлся бы, — и это
        единственное утверждение о привязке, которое можно сделать
        честно: о второй площадке ``/api/bot/account`` говорит лишь то,
        подтверждает ли она вход.
    """
    locked = bool(profile.get("temporarilyLocked"))
    fields = [
        Field("person", "Ник", _text(profile.get("username"))),
        Field("calendar", "Регистрация", texts.format_date(profile.get("registeredAt"))),
        Field("clock", "Последний вход", texts.format_time(profile.get("lastLoginAt"))),
        Field("shield", "Второй фактор", second_factor(profile)),
    ]
    if locked:
        fields.append(Field(
            "warning", "Блокировка до",
            texts.format_time(profile.get("lockedUntil")), theme.TONE_WARN,
        ))

    caption = []
    if locked:
        # Временная блокировка — не украшение экрана, а причина, по которой
        # человек прямо сейчас не может войти. Такое не прячут в картинку.
        caption.append(
            f"{texts.ICON['warning']} Аккаунт временно заблокирован до "
            f"{texts.format_time(profile.get('lockedUntil'))}."
        )
    if donate_url:
        caption.append(f"{texts.ICON['donate']} Поддержать сервер: {donate_url}")

    return Card(
        kind=PROFILE,
        title="Профиль",
        fields=tuple(fields),
        footer=f"Привязано: {platform}" if platform else "",
        caption=tuple(caption),
        text=tuple(texts.profile_lines(profile, donate_url=donate_url)),
    )


def security_card(profile: dict[str, Any]) -> Card:
    """Защита: что включено, что выключено и насколько этого достаточно."""
    approval, approval_tone = _switch(profile.get("loginApproval"))
    notices, notices_tone = _switch(profile.get("notifications"))
    level, level_tone = security_level(profile)

    fields = (
        Field("shield", "Подтверждение входа", approval, approval_tone),
        Field("bell", "Уведомления", notices, notices_tone),
        Field("lock", "Второй фактор", second_factor(profile)),
        Field("person", "CAPTCHA",
              "пройдена" if profile.get("captchaPassed") else "не пройдена",
              theme.TONE_OK if profile.get("captchaPassed") else theme.TONE_MUTED),
    )

    caption: list[str] = []
    if level_tone != theme.TONE_OK:
        # Слабая защита — предупреждение, а не подпись под картинкой. Оно
        # обязано оставаться текстом даже там, где картинка не открылась.
        caption.append(
            f"{texts.ICON['warning']} Уровень защиты: {level}. "
            "Включите подтверждение входа кнопкой."
        )

    return Card(
        kind=SECURITY,
        title="Защита",
        fields=fields,
        footer=f"Уровень защиты: {level}",
        footer_tone=level_tone,
        caption=tuple(caption),
        text=tuple(texts.security_lines(profile)),
    )


def history_card(history: list[dict[str, Any]], limit: int = texts.HISTORY_LIMIT) -> Card:
    """История входов: когда, откуда и чем закончилось.

    Показывается ровно то, что прислал сервер, и не больше ``limit``: список
    ограничен политикой показа, а не размером картинки. Если записей пришло
    больше, лишние не выбрасываются молча — об этом говорит подпись под
    карточкой.

    Адрес приходит уже замаскированным (``ipMasked`` на стороне сервера) и
    здесь не трогается: карточку пересылают, и полный адрес на ней означал бы
    выдачу чужого места жительства одним нажатием «переслать».
    """
    shown = list(history or [])[:max(1, limit)]
    entries = []
    for record in shown:
        success = bool(record.get("success"))
        date, moment = texts.split_time(record.get("at"))
        entries.append(Entry(
            icon="check" if success else "cross",
            columns=(
                Column(date, theme.TONE_ACCENT),
                Column(moment or NOTHING, theme.TONE_TEXT),
                Column(_text(record.get("ip")),
                       theme.TONE_ACCENT if success else theme.TONE_MUTED),
            ),
            sub="" if success else texts.describe_result(record.get("result")),
        ))

    total = len(history or [])
    if total > len(entries):
        footer = f"Показаны последние {len(entries)} из {total}"
    elif entries:
        footer = f"Показаны последние {len(entries)}"
    else:
        # Пустому списку подвал не нужен: объяснение стоит там, где человек
        # ищет записи, — посреди панели.
        footer = ""

    caption = []
    if total > len(entries):
        caption.append(f"Показаны последние {len(entries)} записей из {total}.")

    return Card(
        kind=HISTORY,
        title="История входов",
        entries=tuple(entries),
        footer=footer,
        empty="Входов пока не было",
        caption=tuple(caption),
        text=tuple(texts.history_lines(shown)),
    )


def sessions_card(sessions: list[dict[str, Any]], limit: int = 8) -> Card:
    """Активные сессии: где аккаунт открыт прямо сейчас."""
    shown = list(sessions or [])[:max(1, limit)]
    entries = []
    for session in shown:
        date, moment = texts.split_time(session.get("lastSeenAt"))
        entries.append(Entry(
            icon="screen",
            columns=(
                Column(_text(session.get("type")), theme.TONE_ACCENT),
                Column(_text(session.get("ip")), theme.TONE_TEXT),
            ),
            sub=SEPARATOR.join(filter(None, (
                _text(session.get("server"), "вне сервера"),
                f"{date} {moment}".strip(),
            ))),
        ))

    total = len(sessions or [])
    if total > len(entries):
        footer = f"Показаны {len(entries)} из {total}"
    elif entries:
        footer = f"Всего сессий: {total}"
    else:
        footer = ""

    return Card(
        kind=SESSIONS,
        title="Сессии",
        entries=tuple(entries),
        footer=footer,
        empty="Активных сессий нет",
        caption=(f"Показаны {len(entries)} сессий из {total}.",)
        if total > len(entries) else (),
        text=tuple(texts.session_lines(shown)),
    )


#: Содержимое справки по площадкам. Ссылок здесь нет намеренно: адрес кабинета
#: и страница поддержки задаются развёртыванием, а карточка печётся заранее и
#: лежит в репозитории — запечённый в картинку чужой адрес не поправить ничем.
#: Ссылки остаются в тексте под карточкой, где они к тому же нажимаются.
HELP_SECTIONS: dict[str, tuple[Section, ...]] = {
    "TELEGRAM": (
        Section("link", "Как привязать аккаунт", (
            "1. Зайдите в игру",
            "2. Наберите /telegram",
            "3. Нажмите на ссылку в чате — привяжу сам",
            "Не открылась — пришлите /link КОД",
        )),
        Section("key", "Команды", (
            "/menu — главное меню",
            "/profile — профиль",
            "/security — защита",
            "/history — история входов",
            "/unlink — отвязать Telegram",
        )),
    ),
    "DISCORD": (
        Section("link", "Как привязать аккаунт", (
            "1. Зайдите в игру",
            "2. Наберите /discord",
            "3. Перейдите по ссылке в канал привязки",
            "4. Отправьте боту /link КОД",
        )),
        Section("key", "Команды", (
            "/menu — главное меню",
            "/profile — профиль",
            "/security — защита",
            "/history — история входов",
            "/sessions — активные сессии",
            "/unlink — отвязать Discord",
        )),
    ),
}

#: Единственная строка справки, которую нельзя потерять при сокращении.
#: Направление привязки — часть модели доверия: код выдаётся там, где владение
#: аккаунтом доказуемо, то есть в игре.
HELP_FOOTER = "Код выдаётся только в игре"


def help_card(platform: str) -> Card:
    """Справка выбранной площадки.

    Пользовательских данных на ней нет, поэтому она печётся заранее
    (:mod:`.build`) и во время работы бота только читается с диска. Отпечаток
    считается по тому же правилу, что и у остальных карточек: изменились
    тексты команд — изменилось имя файла, и запечённая картинка перестала
    подходить сама собой.

    Неизвестная площадка — пустая справка, а не исключение: карточка
    оформления не должна решать, работает бот или нет.
    """
    return Card(
        kind=HELP,
        title="Справка",
        sections=HELP_SECTIONS.get(platform.upper(), ()),
        footer=HELP_FOOTER,
    )
