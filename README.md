# KoFAuth — система авторизации KoF Network

Собственная (не AuthMe / не nLogin) production-ready система аутентификации для
сети Minecraft-серверов **KoF Network**: Velocity + Paper 1.21.8, MySQL, Redis,
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
| [`KoFAuth-Telegram`](KoFAuth-Telegram) | Telegram-бот: привязка, вход в одно нажатие, уведомления |
| [`KoFAuth-Discord`](KoFAuth-Discord) | Discord-бот: slash-команды, OAuth2 |
| `KoFAuth-Website` | Личный кабинет (статика, раздаётся WebAPI) |

## Сборка

```bash
./mvnw clean install -DskipTests
```

Требуется JDK 21 или новее. Проект компилируется с `--release 21`, поэтому
собирается и на JDK 24.

### Тесты

```bash
./mvnw clean install
```

359 тестов: 263 модульных и 96 интеграционных. Последние поднимают настоящие
MySQL 8 и Redis 7 через Testcontainers, поэтому нужен запущенный Docker.
Без него — `./mvnw clean install -DskipITs`.

Если Testcontainers пишет `Could not find a valid Docker environment`, при том что
`docker ps` работает, — см. раздел в [KoFAuth-Core/README.md](KoFAuth-Core/README.md).

## Документация

| Документ | Этап |
|----------|------|
| [01 — Архитектура](docs/01-ARCHITECTURE.md) | 1 |
| [02 — База данных](docs/02-DATABASE.md) | 2 |
| [03 — Развёртывание](docs/03-DEPLOYMENT.md) | 5–6 |
| [04 — Эксплуатация](docs/04-PRODUCTION.md) | 16–17 |
| [00 — Журнал разработки](docs/00-DEVELOPMENT-LOG.md) | все |

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
- [x] Этап 11 — Telegram — бот, привязка, подтверждение входа кнопкой, уведомления
- [x] Этап 12 — Discord — slash-команды, подтверждение кнопкой, уведомления в личку
- [x] Этап 13 — Website — личный кабинет без зависимостей, раздаётся WebAPI
- [x] Этап 14 — REST API — 25 эндпоинтов, JWT, Swagger, rate-limit, CSP
- [x] Этап 15 — Админ-команды `/auth` — 10 подкоманд, права через RBAC из базы
- [x] Этап 16 — Docker — compose с MySQL, Redis, WebAPI и ботами
- [x] Этап 17 — Production Ready — health-checks, runbook, чек-лист запуска

## Лицензия

[MIT](LICENSE)
