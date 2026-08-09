#!/usr/bin/env bash
# =============================================================================
#  KoFAuth — установка одной командой.
#
#      cd deploy && ./install.sh
#
#  Что делает:
#    1. проверяет Docker (при отсутствии — предлагает установить);
#    2. создаёт .env и генерирует все секреты;
#    3. собирает jar внутри Docker (Java и Maven на сервере не нужны);
#    4. поднимает стек и ждёт, пока всё станет healthy;
#    5. печатает адреса и следующие шаги.
#
#  Повторный запуск безопасен: существующий .env не трогается,
#  секреты не перегенерируются.
#
#  Ключи:
#    --yes         не задавать вопросов, всё по умолчанию
#    --no-build    не пересобирать jar (использовать то, что уже в artifacts/)
#    --skip-checks не проверять память, диск и занятость портов
# =============================================================================

set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

ASSUME_YES=0
DO_BUILD=1
DO_CHECKS=1
for arg in "$@"; do
    case "$arg" in
        --yes|-y)      ASSUME_YES=1 ;;
        --no-build)    DO_BUILD=0 ;;
        --skip-checks) DO_CHECKS=0 ;;
        --help|-h)     sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "Неизвестный ключ: $arg (см. --help)"; exit 2 ;;
    esac
done

# ------------------------------------------------------------------ оформление

if [ -t 1 ]; then
    BOLD=$'\033[1m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'
    BLUE=$'\033[36m'; OFF=$'\033[0m'
else
    BOLD=''; RED=''; GREEN=''; YELLOW=''; BLUE=''; OFF=''
fi

step()  { printf '\n%s==> %s%s\n' "$BOLD$BLUE" "$*" "$OFF"; }
ok()    { printf '  %s✓%s %s\n' "$GREEN" "$OFF" "$*"; }
warn()  { printf '  %s!%s %s\n' "$YELLOW" "$OFF" "$*"; }
fail()  { printf '\n%sОШИБКА:%s %s\n\n' "$BOLD$RED" "$OFF" "$*" >&2; exit 1; }

ask() {
    # ask <вопрос> <значение-по-умолчанию> -> печатает ответ
    local question="$1" default="${2:-}" answer=''
    if [ "$ASSUME_YES" = 1 ] || [ ! -t 0 ]; then
        printf '%s' "$default"; return
    fi
    read -r -p "  $question${default:+ [$default]}: " answer </dev/tty || true
    printf '%s' "${answer:-$default}"
}

# ------------------------------------------------------------------ генерация секретов

# tr на /dev/urandom закрывает поток раньше, чем head успевает дочитать; при
# pipefail это выглядит как ошибка, хотя данные получены. Гасим локально.
random_alnum() { ( set +o pipefail; LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-32}" ); }

random_base64() {
    if command -v openssl >/dev/null 2>&1; then openssl rand -base64 "${1:-32}" | tr -d '\n'
    else ( set +o pipefail; head -c "${1:-32}" /dev/urandom | base64 | tr -d '\n' ); fi
}

random_hex() {
    if command -v openssl >/dev/null 2>&1; then openssl rand -hex "${1:-32}"
    else ( set +o pipefail; od -An -tx1 -N"${1:-32}" /dev/urandom | tr -d ' \n' ); fi
}

# set_env <КЛЮЧ> <значение> — заменяет строку в .env, не трогая остальное
set_env() {
    local key="$1" value="$2"
    if grep -q "^${key}=" .env; then
        # Разделитель | — в значениях его нет, а / и & встречаются в base64.
        local escaped
        escaped=$(printf '%s' "$value" | sed -e 's/[|\\&]/\\&/g')
        sed -i "s|^${key}=.*|${key}=${escaped}|" .env
    else
        printf '%s=%s\n' "$key" "$value" >> .env
    fi
}

get_env() { grep -m1 "^$1=" .env 2>/dev/null | cut -d= -f2- || true; }

# ------------------------------------------------------------------ 1. Docker

step "Проверяю Docker"

install_docker() {
    step "Устанавливаю Docker"
    command -v curl >/dev/null 2>&1 || fail "нужен curl: apt install curl"
    curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
    if [ "$(id -u)" -eq 0 ]; then sh /tmp/get-docker.sh; else sudo sh /tmp/get-docker.sh; fi
    rm -f /tmp/get-docker.sh
    if [ "$(id -u)" -ne 0 ]; then
        sudo usermod -aG docker "$USER" || true
        warn "Вы добавлены в группу docker. Перезайдите в систему (exit и снова ssh),"
        warn "иначе docker будет требовать sudo."
    fi
}

if ! command -v docker >/dev/null 2>&1; then
    warn "Docker не установлен."
    if [ "$(ask 'Установить Docker сейчас? (y/n)' 'y')" = "y" ]; then
        install_docker
    else
        fail "Без Docker установка невозможна. https://docs.docker.com/engine/install/"
    fi
fi

docker info >/dev/null 2>&1 || {
    if command -v systemctl >/dev/null 2>&1; then
        warn "Демон Docker не отвечает, пробую запустить"
        sudo systemctl start docker || true
        sleep 3
    fi
    docker info >/dev/null 2>&1 || fail "Демон Docker не отвечает. Проверьте: systemctl status docker"
}
ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?')"

docker compose version >/dev/null 2>&1 \
    || fail "Нет плагина docker compose (v2). Установите docker-compose-plugin: apt install docker-compose-plugin"
ok "Compose $(docker compose version --short 2>/dev/null || echo '?')"

DC="docker compose"

# ------------------------------------------------------------------ 2. ресурсы и порты

if [ "$DO_CHECKS" = 1 ]; then
    step "Проверяю сервер"

    if [ -r /proc/meminfo ]; then
        mem_mb=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 ))
        if [ "$mem_mb" -lt 3500 ]; then
            warn "Оперативной памяти ${mem_mb} МБ. Полному стеку нужно ~4 ГБ."
            warn "Уменьшите HEAP_LOBBY и HEAP_LIMBO в .env либо добавьте памяти."
        else
            ok "Память: ${mem_mb} МБ"
        fi
    fi

    disk_gb=$(df -BG --output=avail . 2>/dev/null | tail -1 | tr -dc '0-9' || echo 0)
    if [ -n "$disk_gb" ] && [ "$disk_gb" -lt 12 ] 2>/dev/null; then
        warn "Свободно ${disk_gb} ГБ. Образам, миру и зависимостям Maven нужно ~12 ГБ."
    else
        ok "Диск: ${disk_gb} ГБ свободно"
    fi

    port_busy() {
        if command -v ss >/dev/null 2>&1; then ss -ltn "( sport = :$1 )" 2>/dev/null | grep -q LISTEN
        elif command -v netstat >/dev/null 2>&1; then netstat -ltn 2>/dev/null | grep -q ":$1 "
        else return 1; fi
    }
    for p in 25565 8080; do
        if port_busy "$p"; then
            warn "Порт $p занят. Смените MINECRAFT_PORT/WEBAPI_PORT в .env или освободите порт."
        fi
    done
fi

# ------------------------------------------------------------------ 3. .env

step "Готовлю конфигурацию"

if [ -f .env ]; then
    ok ".env уже есть — секреты сохранены как есть"
else
    cp .env.example .env
    chmod 600 .env
    ok "Создан .env"
fi

# Пустые обязательные значения заполняем; заполненные не трогаем никогда —
# смена ENCRYPTION_KEY у работающей сети ломает TOTP и токены Discord.
generated=0
fill_secret() {
    local key="$1" value="$2"
    if [ -z "$(get_env "$key")" ]; then set_env "$key" "$value"; generated=$((generated + 1)); fi
}
fill_secret MYSQL_ROOT_PASSWORD "$(random_alnum 32)"
fill_secret MYSQL_PASSWORD      "$(random_alnum 32)"
fill_secret REDIS_PASSWORD      "$(random_alnum 32)"
fill_secret RCON_PASSWORD       "$(random_alnum 24)"
fill_secret ENCRYPTION_KEY      "$(random_base64 32)"
fill_secret JWT_SECRET          "$(random_hex 32)"
# Ключ доступа ботов к /api/bot. Пустое значение выключает эту поверхность
# целиком, поэтому он генерируется всегда, даже если боты пока не нужны.
fill_secret BOT_API_KEY          "$(random_hex 32)"
fill_secret FORWARDING_SECRET   "$(random_hex 24)"
[ "$generated" -gt 0 ] && ok "Сгенерировано секретов: $generated" || ok "Секреты на месте"

# --------------------------------------------------- необязательные возможности

profiles="$(get_env COMPOSE_PROFILES)"
add_profile() { case ",$profiles," in *",$1,"*) ;; *) profiles="${profiles:+$profiles,}$1" ;; esac; }
# Профиль lobby приходит из .env.example при первой установке. Обратно его
# здесь не добавляют: администратор мог убрать его осознанно, подключив свой
# игровой сервер, и возврат поднял бы второй, на который никто не ходит.
has_profile() { case ",$profiles," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

if [ "$ASSUME_YES" = 0 ] && [ -t 0 ]; then
    printf '\n  %sНеобязательное — можно пропустить (Enter) и настроить позже в .env%s\n' "$BOLD" "$OFF"

    if [ -z "$(get_env TELEGRAM_BOT_TOKEN)" ]; then
        t=$(ask 'Токен Telegram-бота от @BotFather' '')
        if [ -n "$t" ]; then
            set_env TELEGRAM_BOT_TOKEN "$t"
            set_env TELEGRAM_BOT_USERNAME "$(ask 'Имя бота без собачки' '')"
            add_profile telegram
            ok "Telegram включён"
        fi
    fi

    if [ -z "$(get_env DISCORD_BOT_TOKEN)" ]; then
        d=$(ask 'Токен Discord-бота' '')
        if [ -n "$d" ]; then
            set_env DISCORD_BOT_TOKEN "$d"
            set_env DISCORD_GUILD_ID "$(ask 'ID сервера Discord (пусто — команды глобально)' '')"
            add_profile discord
            ok "Discord включён"
        fi
    fi

    if [ -z "$(get_env DOMAIN)" ]; then
        dm=$(ask 'Домен для личного кабинета по HTTPS (например auth.example.com)' '')
        if [ -n "$dm" ]; then
            set_env DOMAIN "$dm"
            set_env ACME_EMAIL "$(ask 'E-mail для Let'\''s Encrypt' "admin@$dm")"
            # За обратным прокси адрес клиента приходит заголовком, иначе все
            # запросы выглядели бы приходящими с адреса Caddy — и ограничение
            # скорости считало бы их одним клиентом.
            set_env TRUST_PROXY_HEADER true
            set_env WEBAPI_BIND 127.0.0.1
            set_env DISCORD_OAUTH_REDIRECT_URI "https://$dm/api/discord/callback"
            add_profile web
            ok "HTTPS включён для $dm"
        fi
    fi
fi

# Бот без токена завершится с понятной записью в логе, поэтому профиль
# включаем строго по факту наличия токена. Вместе с профилем поднимается флаг
# для остальных процессов: без него команда привязки на прокси отвечает
# «не подключён», и бот оказывается недостижим для игрока.
if [ -n "$(get_env TELEGRAM_BOT_TOKEN)" ]; then
    add_profile telegram
    set_env TELEGRAM_ENABLED true
fi
if [ -n "$(get_env DISCORD_BOT_TOKEN)" ]; then
    add_profile discord
    set_env DISCORD_ENABLED true
fi
if [ -n "$(get_env DOMAIN)" ]; then add_profile web; fi
set_env COMPOSE_PROFILES "$profiles"
if [ -n "$profiles" ]; then ok "Профили: $profiles"; fi

mkdir -p artifacts data/config

# ------------------------------------------------------------------ 4. сборка

if [ "$DO_BUILD" = 1 ]; then
    step "Собираю jar внутри Docker (первый раз — 5–15 минут, дальше быстрее)"
    $DC --profile build run --rm --no-deps builder \
        || fail "Сборка не прошла. Полный вывод выше; частая причина — нет доступа к репозиторию Maven."
    # Контейнер писал target/ от root: без этого хозяйская сборка потом
    # упрётся в отказ в доступе.
    if [ "$(id -u)" -ne 0 ]; then
        sudo chown -R "$(id -u):$(id -g)" ../KoFAuth-*/target artifacts 2>/dev/null || true
    fi
    ok "Артефакты готовы"
else
    for j in kofauth-velocity kofauth-paper kofauth-webapi; do
        [ -f "artifacts/$j.jar" ] || fail "нет artifacts/$j.jar — запустите без --no-build"
    done
    ok "Использую готовые артефакты"
fi

# ------------------------------------------------------------------ 5. запуск

step "Поднимаю стек"
$DC up -d

step "Жду готовности (Paper при первом запуске генерирует мир, это до 5 минут)"

# Состояние берём через docker inspect, а не через `compose ps --format`:
# произвольные шаблоны Go этот подкоманд принимает не во всех версиях v2,
# и на части серверов вывод оказался бы пустым, а ожидание — вечным.
wait_healthy() {
    local service="$1" limit="${2:-600}" waited=0 cid state
    while [ "$waited" -lt "$limit" ]; do
        cid=$($DC ps -q "$service" 2>/dev/null | head -1)
        if [ -n "$cid" ]; then
            state=$(docker inspect -f \
                '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
                "$cid" 2>/dev/null || echo unknown)
            case "$state" in
                healthy)  printf '\n'; ok "$service — готов"; printf '  '; return 0 ;;
                running)  printf '\n'; ok "$service — запущен (проверки здоровья нет)"; printf '  '; return 0 ;;
                exited|dead)
                    printf '\n'; warn "$service — упал:"
                    $DC logs --tail 40 "$service" || true
                    printf '  '; return 1 ;;
            esac
        fi
        printf '.'
        sleep 5; waited=$((waited + 5))
    done
    printf '\n'; warn "$service не стал готов за ${limit} с. Последние строки лога:"
    $DC logs --tail 40 "$service" || true
    printf '  '
    return 1
}

failed=0
printf '  '
services="mysql redis webapi velocity limbo"
if has_profile lobby; then services="$services lobby"; fi
for s in $services; do
    wait_healthy "$s" || failed=1
done

# ------------------------------------------------------------------ 6. итог

ip=$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || echo 'IP_СЕРВЕРА')
mc_port=$(get_env MINECRAFT_PORT); mc_port=${mc_port:-25565}
web_port=$(get_env WEBAPI_PORT);   web_port=${web_port:-8080}
domain=$(get_env DOMAIN)

if [ "$failed" = 0 ]; then
    printf '\n%s  KoF Network поднят.%s\n\n' "$BOLD$GREEN" "$OFF"
else
    printf '\n%s  Стек поднят, но не все службы готовы — смотрите вывод выше.%s\n\n' "$BOLD$YELLOW" "$OFF"
fi

printf '  %sЗаходить в игру:%s   %s:%s\n' "$BOLD" "$OFF" "$ip" "$mc_port"
if [ -n "$domain" ]; then
    printf '  %sЛичный кабинет:%s   https://%s\n' "$BOLD" "$OFF" "$domain"
    printf '  %sSwagger:%s          https://%s/swagger-ui.html\n' "$BOLD" "$OFF" "$domain"
else
    printf '  %sЛичный кабинет:%s   http://%s:%s\n' "$BOLD" "$OFF" "$ip" "$web_port"
    printf '  %sSwagger:%s          http://%s:%s/swagger-ui.html\n' "$BOLD" "$OFF" "$ip" "$web_port"
fi

cat <<TXT

  ${BOLD}Дальше:${OFF}
    1. Зайдите в игру и зарегистрируйтесь:  /register пароль пароль
    2. Выдайте себе права администратора:   ./kofauth.sh admin ВашНик
    3. Управление:                          ./kofauth.sh status | logs | stop | update
    4. Консоль прокси для команд /auth:     ./kofauth.sh console

  ${BOLD}${YELLOW}Сохраните .env вне сервера.${OFF} Потеря ENCRYPTION_KEY делает нечитаемыми
  секреты TOTP и токены Discord — восстановленный дамп базы окажется
  наполовину бесполезным.

TXT
