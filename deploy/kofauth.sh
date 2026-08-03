#!/usr/bin/env bash
# =============================================================================
#  KoFAuth — управление стеком.
#
#      ./kofauth.sh <команда> [аргументы]
#
#  Команды:
#    start                запустить всё
#    stop                 остановить (данные сохраняются)
#    restart [служба]     перезапустить всё или одну службу
#    status               что поднято и в каком состоянии
#    logs [служба]        логи в реальном времени; без имени — всех сразу
#    console              консоль прокси: там выполняются команды auth —
#                         в том числе auth resetpassword <ник> <пароль>
#                         (выйти, не остановив сервер: Ctrl-P затем Ctrl-Q)
#    update               подтянуть изменения, пересобрать jar, перезапустить
#    rebuild              только пересобрать jar и перезапустить процессы KoFAuth
#    admin <ник> [роль]   выдать роль (по умолчанию admin; ещё есть owner)
#    unadmin <ник> <роль> снять роль
#    db [запрос]          консоль MySQL или один запрос
#    backup               дамп базы и копия .env в backups/
#    restore <файл>       восстановить базу из дампа
#    destroy              снести всё вместе с данными (спросит подтверждение)
# =============================================================================

set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

if [ -t 1 ]; then
    BOLD=$'\033[1m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; OFF=$'\033[0m'
else
    BOLD=''; RED=''; GREEN=''; YELLOW=''; OFF=''
fi
ok()   { printf '  %s✓%s %s\n' "$GREEN" "$OFF" "$*"; }
warn() { printf '  %s!%s %s\n' "$YELLOW" "$OFF" "$*"; }
fail() { printf '%sОШИБКА:%s %s\n' "$BOLD$RED" "$OFF" "$*" >&2; exit 1; }

[ -f .env ] || fail "нет .env — запустите ./install.sh"

DC="docker compose"
get_env() { grep -m1 "^$1=" .env 2>/dev/null | cut -d= -f2- || true; }

# Пароль передаём переменной окружения, а не ключом -p: аргументы командной
# строки видны любому пользователю сервера в ps.
mysql_run() {
    $DC exec -T -e MYSQL_PWD="$(get_env MYSQL_ROOT_PASSWORD)" mysql \
        mysql -u root --default-character-set=utf8mb4 kofauth "$@"
}

# SQL-шаблон с подстановкой ника: значение уходит через пользовательскую
# переменную, поэтому кавычки и апострофы в нике ничего не ломают.
#
# SET NAMES обязателен. Клиент MySQL 8 по умолчанию соединяется с
# utf8mb4_0900_ai_ci, а колонки схемы — utf8mb4_unicode_ci, и сравнение
# переменной с колонкой падает на «Illegal mix of collations».
sql_with_username() {
    local username="$1" statement="$2"
    printf 'SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;\nSET @nick = %s;\n%s\n' \
        "$(quote_sql "$username")" "$statement" | mysql_run
}

quote_sql() { printf "'%s'" "$(printf '%s' "$1" | sed "s/\\\\/\\\\\\\\/g; s/'/\\\\'/g")"; }

require_running() {
    $DC ps --services --filter status=running 2>/dev/null | grep -qx mysql \
        || fail "стек не запущен — ./kofauth.sh start"
}

cmd="${1:-help}"; shift || true

case "$cmd" in

    start)
        $DC up -d
        ok "Запущено. Состояние: ./kofauth.sh status"
        ;;

    stop)
        $DC stop
        ok "Остановлено. Данные на месте, ./kofauth.sh start поднимет обратно."
        ;;

    restart)
        if [ $# -gt 0 ]; then $DC restart "$@"; else $DC restart; fi
        ok "Перезапущено"
        ;;

    status|ps)
        $DC ps
        printf '\n'
        if $DC ps --services --filter status=running 2>/dev/null | grep -qx webapi; then
            printf '  %sHealth:%s\n' "$BOLD" "$OFF"
            $DC exec -T webapi wget -qO- http://127.0.0.1:8080/actuator/health 2>/dev/null \
                || warn "webapi не отвечает"
            printf '\n'
        fi
        ;;

    logs)
        if [ $# -eq 0 ]; then $DC logs --tail 100 -f; else $DC logs --tail 200 -f "$@"; fi
        ;;

    console)
        printf '%sКонсоль Velocity. Команды: auth info, auth player <ник>, auth reload …%s\n' "$BOLD" "$OFF"
        printf '%sВыход БЕЗ остановки сервера: Ctrl-P, затем Ctrl-Q%s\n\n' "$YELLOW" "$OFF"
        docker attach "$($DC ps -q velocity)"
        ;;

    update)
        if [ -d ../.git ]; then
            printf '%s==> Подтягиваю изменения%s\n' "$BOLD" "$OFF"
            git -C .. pull --ff-only || warn "git pull не прошёл — собираю то, что есть локально"
        fi
        exec "$0" rebuild
        ;;

    rebuild)
        printf '%s==> Пересобираю jar%s\n' "$BOLD" "$OFF"
        $DC --profile build run --rm --no-deps builder || fail "сборка не прошла"
        if [ "$(id -u)" -ne 0 ]; then
            sudo chown -R "$(id -u):$(id -g)" ../KoFAuth-*/target artifacts 2>/dev/null || true
        fi
        printf '%s==> Перезапускаю с новыми артефактами%s\n' "$BOLD" "$OFF"
        # Порядок важен: сначала прокси (он применяет миграции через webapi),
        # затем Limbo, затем игровые серверы.
        $DC up -d --force-recreate webapi
        $DC up -d --force-recreate velocity limbo lobby
        $DC up -d
        ok "Обновлено"
        ;;

    admin|unadmin)
        require_running
        nick="${1:-}"; role="${2:-admin}"
        [ -n "$nick" ] || fail "укажите ник: ./kofauth.sh $cmd Ник [роль]"
        case "$role" in
            player|vip|premium|helper|moderator|admin|owner) ;;
            *) fail "неизвестная роль '$role'. Есть: player, vip, premium, helper, moderator, admin, owner" ;;
        esac

        if [ "$cmd" = admin ]; then
            sql_with_username "$nick" "
                INSERT IGNORE INTO user_roles (account_id, role_id)
                SELECT u.id, r.id FROM users u JOIN roles r ON r.name = $(quote_sql "$role")
                WHERE u.lower_username = LOWER(@nick);"
        else
            sql_with_username "$nick" "
                DELETE ur FROM user_roles ur
                JOIN users u ON u.id = ur.account_id
                JOIN roles r ON r.id = ur.role_id
                WHERE u.lower_username = LOWER(@nick) AND r.name = $(quote_sql "$role");"
        fi

        # Показываем итог, а не «команда выполнена»: пустой результат означает,
        # что такого ника в базе нет — а это самая частая причина «не работает».
        printf '  Роли игрока %s:\n' "$nick"
        sql_with_username "$nick" "
            SELECT r.name AS role, r.display_name AS title
            FROM users u
            LEFT JOIN user_roles ur ON ur.account_id = u.id
            LEFT JOIN roles r ON r.id = ur.role_id
            WHERE u.lower_username = LOWER(@nick);" \
            | sed 's/^/    /'
        printf '\n  Если список пуст — игрок с таким ником ещё не зарегистрирован.\n'
        ;;

    db)
        require_running
        if [ $# -gt 0 ]; then mysql_run -e "$*"; else
            $DC exec -e MYSQL_PWD="$(get_env MYSQL_ROOT_PASSWORD)" mysql \
                mysql -u root --default-character-set=utf8mb4 kofauth
        fi
        ;;

    backup)
        require_running
        mkdir -p backups
        file="backups/kofauth-$(date +%Y%m%d-%H%M%S).sql.gz"
        # --single-transaction снимает согласованный снимок без блокировки таблиц:
        # игроки не заметят снятия копии.
        $DC exec -T -e MYSQL_PWD="$(get_env MYSQL_ROOT_PASSWORD)" mysql \
            mysqldump -u root --single-transaction --routines --triggers \
            --default-character-set=utf8mb4 kofauth | gzip > "$file"
        ok "База: $file ($(du -h "$file" | cut -f1))"
        cp .env "backups/env-$(date +%Y%m%d-%H%M%S).backup"
        chmod 600 backups/env-*.backup
        warn "Рядом сохранена копия .env — без ENCRYPTION_KEY дамп наполовину бесполезен:"
        warn "секреты TOTP и токены Discord зашифрованы им. Держите обе копии вне сервера."
        ;;

    restore)
        require_running
        file="${1:-}"
        [ -f "$file" ] || fail "укажите файл дампа: ./kofauth.sh restore backups/kofauth-….sql.gz"
        printf '%sВосстановление ПЕРЕЗАПИШЕТ текущую базу.%s Введите YES: ' "$BOLD$RED" "$OFF"
        read -r confirm
        [ "$confirm" = "YES" ] || fail "отменено"
        if [ "${file##*.}" = "gz" ]; then gunzip -c "$file" | mysql_run; else mysql_run < "$file"; fi
        ok "База восстановлена. Перезапускаю процессы."
        $DC restart webapi velocity limbo lobby
        ;;

    destroy)
        printf '%sБудут удалены контейнеры, тома и ВСЕ данные: аккаунты, миры, история.%s\n' "$BOLD$RED" "$OFF"
        printf 'Введите DELETE для подтверждения: '
        read -r confirm
        [ "$confirm" = "DELETE" ] || fail "отменено"
        $DC --profile bots --profile telegram --profile discord --profile web down -v
        ok "Удалено. .env и backups/ остались на месте."
        ;;

    help|--help|-h)
        sed -n '3,25p' "$0" | sed 's/^# \{0,1\}//'
        ;;

    *)
        fail "неизвестная команда '$cmd'. ./kofauth.sh help"
        ;;
esac
