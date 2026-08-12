# KoFAuth — система авторизации KoF Network

Собственная (не AuthMe / не nLogin) production-ready система аутентификации для
сети Minecraft-серверов **KoF Network**: Velocity + Paper 1.21.8+, MySQL, Redis,
REST API, Telegram- и Discord-боты, TOTP, личный кабинет.

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Paper](https://img.shields.io/badge/Paper-1.21.8+-blue)](https://papermc.io/)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

---

## Модули

| Модуль | Что делает |
|--------|-----------|
| [`KoFAuth-API`](KoFAuth-API) | Контракты: доменная модель, DTO, интерфейсы сервисов и репозиториев, события |
| [`KoFAuth-Core`](KoFAuth-Core) | Реализация: конфигурация, MySQL/Hikari/Flyway, Redis, криптография, JWT, TOTP, event bus |
| [`KoFAuth-Velocity`](KoFAuth-Velocity) | Плагин прокси: маршрутизация в Limbo, блокировка pre-login трафика |
| [`KoFAuth-Paper`](KoFAuth-Paper) | Плагин Paper: Limbo-мир, защита, CAPTCHA GUI, команды |
| [`KoFAuth-WebAPI`](KoFAuth-WebAPI) | Spring Boot REST API + Swagger + раздача сайта |
| [`bots/`](bots) | Боты Telegram и Discord на Python: привязка, подтверждение входа кнопкой, уведомления |
| `KoFAuth-Website` | Личный кабинет (статика, раздаётся WebAPI) |

## Установка на сервер

Нужен только SSH-доступ к серверу с Ubuntu или Debian. Java, Maven, MySQL и
серверные ядра ставить не требуется — всё поднимается в Docker:

```bash
git clone https://github.com/Renamekk/KoFNetwork-Auth.git
cd KoFNetwork-Auth/deploy
./install.sh
```

Скрипт поставит Docker при его отсутствии, сгенерирует все пароли и ключи,
соберёт jar в контейнере и поднимет сеть: прокси Velocity, Limbo, лобби,
MySQL, Redis, REST API с личным кабинетом. Через 10–20 минут можно заходить
в игру на `IP:25565` и регистрироваться.

Пошагово, с доменом, ботами, почтой и разбором ошибок —
**[docs/05-QUICKSTART.md](docs/05-QUICKSTART.md)**.

Дальнейшее управление:

```bash
./kofauth.sh status | logs | console | update | backup
```

## Права администратора

Полный доступ к `/auth` — все подкоманды без исключений и без разбора узлов —
дают четыре источника:

1. консоль прокси;
2. **OP на игровом сервере**;
3. перечень `velocity.yml → admin.operators` (ники или UUID);
4. право `kofauth.admin` у стороннего плагина прав (LuckPerms и подобные).

Роли KoFAuth из базы работают по-прежнему и дают доступ **по отдельным узлам**:
ими выдают модератору ровно `/auth player`, не открывая `/auth export`.

**OP требует общего Redis.** OP — понятие Bukkit, он лежит в `ops.json`
игрового сервера. Velocity собственной системы прав не имеет вовсе и без
стороннего плагина отвечает `UNDEFINED` на любой запрос, а файла соседнего
процесса (нередко и соседней машины) не видит. Признак переносит плагин Paper
через общее хранилище состояния — единственное, что у прокси и бэкенда есть
общего. При `cache.enabled: false` у каждого процесса своя память, отметка до
прокси не доходит, и администраторов задаёт `admin.operators`.

Проверить, работает ли перенос, — `/auth info`, строка «Права по OP».
Выключается целиком в `paper.yml → operators.publish`.

## Сборка из исходников

```bash
./mvnw clean install -DskipTests
```

Требуется JDK 21 или новее. Проект компилируется с `--release 21`, поэтому
собирается и на JDK 24.

### Тесты

```bash
./mvnw clean install
```

413 модульных тестов плюс интеграционные. Последние поднимают настоящие
MySQL 8 и Redis 7 через Testcontainers, поэтому нужен запущенный Docker.
Без него — `./mvnw clean install -DskipITs`, это те самые 413.

| Модуль | Модульных тестов |
|---|---|
| `KoFAuth-API` | 119 |
| `KoFAuth-Core` | 171 |
| `KoFAuth-Velocity` | 71 |
| `KoFAuth-WebAPI` | 39 |
| `KoFAuth-Paper` | 13 |

Если Testcontainers пишет `Could not find a valid Docker environment`, при том что
`docker ps` работает, — см. раздел в [KoFAuth-Core/README.md](KoFAuth-Core/README.md).

### Боты

Боты на Python живут отдельно от Maven и собираются своим набором:

```bash
cd bots && python -m pytest tests -q
```

47 тестов. Работа с самими API Telegram и Discord не тестируется: она сводится
к вызовам библиотек, а мокать их означало бы проверять моки.

## Документация

| Документ | О чём |
|----------|------|
| [05 — Установка на сервер](docs/05-QUICKSTART.md) | Пошагово: от пустого VPS до работающей сети |
| [01 — Архитектура](docs/01-ARCHITECTURE.md) | Устройство системы, диаграммы |
| [02 — База данных](docs/02-DATABASE.md) | Схема, 17 таблиц |
| [03 — Развёртывание](docs/03-DEPLOYMENT.md) | Ручная установка, команды `/auth` |
| [04 — Эксплуатация](docs/04-PRODUCTION.md) | Наблюдаемость, нагрузка, инциденты |
| [00 — Журнал разработки](docs/00-DEVELOPMENT-LOG.md) | Что и почему сделано именно так |

## Статус разработки

- [x] Этап 1 — Проектирование архитектуры
- [x] Этап 2 — База данных — проверено на MySQL 8.0.46: 17 таблиц, 21 FK, 66 индексов
- [x] Этап 3 — Core — 13 репозиториев, 10 сервисов, сквозной тест регистрации и входа
- [x] Этап 4 — API — 119 тестов, сборка зелёная
- [x] Этап 5 — Velocity — гейт аутентификации, маршрутизация, `/login` и `/register`
- [x] Этап 6 — Limbo — пустой замороженный мир, 12 защитных правил
- [x] Этап 7 — Регистрация — политика паролей, лимиты на IP, роль по умолчанию
- [x] Этап 8 — Авторизация — блокировки, 2FA, перехэширование, постоянное время ответа
- [x] Этап 9 — CAPTCHA — GUI-сетка и текстовый режим, выдача при входе и при подозрительной активности
- [x] Этап 10 — Email — `/email`, восстановление пароля по коду, уведомления о входе
- [x] Этап 11 — Telegram — бот, привязка, подтверждение кнопкой, уведомления
- [x] Этап 12 — Discord — slash-команды, подтверждение кнопкой, OAuth2, уведомления в личку
- [x] Этап 13 — Website — личный кабинет без зависимостей, раздаётся WebAPI
- [x] Этап 14 — REST API — 31 эндпоинт, JWT, Swagger, rate-limit, CSRF, CSP
- [x] Этап 15 — Админ-команды `/auth` — 14 подкоманд, RBAC из базы плюс полный доступ по OP
- [x] Этап 16 — Docker — compose с MySQL, Redis, WebAPI и ботами
- [x] Этап 17 — Production Ready — health-checks, runbook, чек-лист запуска

## Лицензия

[MIT](LICENSE)
