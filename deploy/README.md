# KoFAuth — развёртывание

Всё, что нужно, чтобы сеть заработала на чистом сервере. Подробная инструкция —
[docs/05-QUICKSTART.md](../docs/05-QUICKSTART.md), здесь короткая справка.

```bash
./install.sh
```

## Что здесь лежит

| Файл | Назначение |
|---|---|
| `install.sh` | Установка: Docker, секреты, сборка, запуск, проверка |
| `kofauth.sh` | Управление: start/stop/logs/console/update/backup/admin |
| `docker-compose.yml` | Весь стек: база, кэш, веб-API, прокси, Limbo, лобби, боты, HTTPS |
| `.env.example` | Образец настроек; рабочий `.env` создаёт `install.sh` |
| `config/velocity/` | `velocity.toml` и секрет форвардинга |
| `config/caddy/` | Обратный прокси с автоматическим HTTPS |
| `artifacts/` | Собранные jar (в git не попадают) |
| `data/config/` | YAML-конфигурация KoFAuth, общая для веб-API и ботов |
| `backups/` | Дампы базы и копии `.env` |

## Службы

| Служба | Профиль | Порт наружу |
|---|---|---|
| `mysql`, `redis` | — | нет, только внутри сети Docker |
| `webapi` | — | `8080` (или через `caddy`) |
| `velocity` | — | `25565` |
| `limbo`, `lobby` | — | нет |
| `telegram` | `telegram` или `bots` | нет |
| `discord` | `discord` или `bots` | нет |
| `caddy` | `web` | `80`, `443` |
| `builder` | `build` | разовая сборка, не поднимается |

Какие профили включены, задаёт `COMPOSE_PROFILES` в `.env`.

## Частые операции

```bash
./kofauth.sh admin Ник            # выдать права администратора
./kofauth.sh console              # консоль прокси: команды auth
./kofauth.sh logs velocity        # логи одной службы
./kofauth.sh db "SELECT COUNT(*) FROM users;"
./kofauth.sh backup               # дамп базы + копия .env
./kofauth.sh update               # git pull, пересборка, перезапуск
```

## Что нельзя менять после запуска

`ENCRYPTION_KEY` — им зашифрованы секреты TOTP и токены Discord. Смена ключа
не «сбрасывает» их, а делает нечитаемыми: у аккаунтов перестанет работать второй
фактор, и отключить его штатным путём будет нельзя. Храните `.env` вместе с
резервной копией базы, но не внутри неё.
