# KoFAuth — развёртывание

Всё, что нужно, чтобы сеть заработала на чистом сервере. Подробная инструкция —
[docs/05-QUICKSTART.md](../docs/05-QUICKSTART.md), здесь короткая справка.

```bash
./install.sh
```

## Windows: один переносимый `deploy.bat`

Для Windows в корне репозитория есть [`deploy.bat`](../deploy.bat). Сначала в
репозитории соберите релизный комплект:

```powershell
.\mvnw.cmd -B -ntp clean verify -DskipITs
.\deploy.bat package
```

Затем рядом с ядром Paper/Purpur положите сам `deploy.bat` и всю папку
`deploy` (включая три JAR из `deploy\artifacts`):

```text
server\
  server.jar
  deploy.bat
  deploy\
    docker-compose.yml
    .env.example
    artifacts\
      kofauth-paper.jar
      kofauth-velocity.jar
      kofauth-webapi.jar
```

Нужны Java 21+ и запущенный Docker Desktop. Первый запуск `deploy.bat`
попросит принять Minecraft EULA, создаст отдельные постоянные секреты и
конфиги в `_kofauth`, скачает Velocity с проверкой SHA-256, подготовит Limbo,
MySQL/Redis/WebAPI и создаст управляющие BAT-файлы. Повторный запуск сравнивает
SHA-256 и меняет/перезапускает только затронутые компоненты; миры, пользовательские
настройки, секреты, контейнеры, БД и Docker-тома не удаляются.

Основные команды:

```powershell
.\deploy.bat                 # установить или безопасно обновить
.\deploy.bat --dry-run       # показать план без изменений
.\deploy.bat status
.\deploy.bat start-all
.\deploy.bat stop-all
.\deploy.bat restart-all
```

В корне появятся `KOFAUTH-START-ALL.bat`, `KOFAUTH-STOP-ALL.bat`,
`KOFAUTH-RESTART-ALL.bat`, а отдельные команды компонентов будут лежать в
`_kofauth\commands`. Если переносите новый релиз, замените только папку
`deploy` и сам `deploy.bat`, затем снова запустите `deploy.bat`: постоянное
состояние хранится отдельно в `_kofauth`.

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
