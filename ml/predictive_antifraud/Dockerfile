FROM python:3.12-slim

WORKDIR /srv
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .

# Модели монтируются томом или копируются в образ на этапе выкладки.
COPY models ./models
ENV PA_MODELS_DIR=/srv/models

EXPOSE 8000
CMD ["uvicorn", "predictive_antifraud.main:app", "--host", "0.0.0.0", "--port", "8000"]
