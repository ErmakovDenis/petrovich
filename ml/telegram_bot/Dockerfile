FROM python:3.12-slim

WORKDIR /srv
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .

# Короткие ключи кнопок (SQLite): в docker compose каталог — том telegram_data. Можно переопределить в .env.
ENV TB_DATA_PATH=/srv/data/bot.db
# Кэш шрифтов matplotlib (графики «Откуда цифра»).
ENV MPLCONFIGDIR=/tmp/matplotlib

CMD ["python", "-m", "telegram_bot"]
