FROM python:3.12-slim

WORKDIR /srv
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .

# База хранилища аномалий и решений (SQLite); в docker compose каталог — том fleet_data. Можно переопределить в .env.
ENV FS_DB_URL=sqlite:////srv/data/fleet.db

EXPOSE 8000
CMD ["uvicorn", "--factory", "fleet_service.main:create_app", "--host", "0.0.0.0", "--port", "8000"]
