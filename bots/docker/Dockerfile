# Общий образ для обоих ботов: различие только в модуле, который запускается.
# Два почти одинаковых образа означали бы два места, где нужно не забыть
# обновить версию Python.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY packages/kofauth_common /app/packages/kofauth_common
COPY apps/telegram_bot /app/apps/telegram_bot
COPY apps/discord_bot /app/apps/discord_bot

# Пакеты ставятся ссылками: правка кода при разработке не требует пересборки
# образа, если каталог смонтирован.
RUN pip install --no-cache-dir -e /app/packages/kofauth_common \
 && pip install --no-cache-dir --no-deps -e /app/apps/telegram_bot \
 && pip install --no-cache-dir --no-deps -e /app/apps/discord_bot

# Какой бот запускать, задаёт команда сервиса в docker-compose:
#   python -m telegram_bot   |   python -m discord_bot
CMD ["python", "-m", "telegram_bot"]
