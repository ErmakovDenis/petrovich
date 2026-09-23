# Предиктивная аналитика и антифрод

FastAPI-сервис для «Петрович Телеметрия». Принимает телеметрию одной машины за период в том же виде, что
`VehicleTelemetry` в приложении, и возвращает аномалии в формате `Anomaly` (id стабилен — приложение
дедуплицирует по нему).

- **Предиктивная аналитика** (`POST /v1/predictive/analyze`) — признаки деградации двигателя, охлаждения, АКБ до отказа.
- **Антифрод** (`POST /v1/antifraud/check`) — сливы и фиктивные заправки топлива, расход, не согласующийся с пробегом.

Пока модели не обучены, сервис работает и отвечает `{"ready": false, "anomalies": []}` — как `isReady = false`
у `MlAnomalyDetector`.

## Запуск

```bash
cd ml/predictive_antifraud
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt          # или pip install -e ".[dev]"
uvicorn predictive_antifraud.main:app --app-dir src --reload     # http://127.0.0.1:8000/docs
pytest
```

Docker: `docker build -t petrovich-pa . && docker run -p 8000:8000 -v $PWD/models:/srv/models petrovich-pa`.

Настройки — переменные окружения с префиксом `PA_` (см. `.env.example`).

## Эндпоинты

| Метод | Путь | Описание |
|---|---|---|
| GET | `/health` | liveness |
| GET | `/ready` | какие модели загружены |
| POST | `/v1/predictive/analyze` | предиктивная аналитика |
| POST | `/v1/antifraud/check` | антифрод |

При заданном `PA_API_KEY` эндпоинты `/v1/*` требуют заголовок `X-API-Key`.

## Структура

```
src/predictive_antifraud/
  main.py              create_app(), загрузка моделей в lifespan
  config.py            настройки (PA_*)
  api/                 роуты и зависимости
  schemas/             VehicleTelemetry (вход), Anomaly / DetectionResponse (выход)
  features/            матрица признаков время × параметры, имена параметров AutoGRAPH
  models/              интерфейс ScoringModel и реестр моделей
  services/            признаки → оценки → эпизоды → Anomaly; профили predictive / antifraud
models/                артефакты моделей (не коммитятся)
tests/
```

## Особенности данных AutoGRAPH

- При выключенном зажигании (`DIgnition` / `DIgnitionCAN`) CAN-параметры приходят нулями, а обороты `Rotation`
  «замирают». `features/extractor.py` считает такие значения пропусками и отдаёт маску `observed`.
- `BattaryVOLTAGE` есть только у Урал NEXT. `FeatureMatrix.reindex()` дополняет отсутствующие столбцы нулями
  с `observed = False`, чтобы у модели был фиксированный набор признаков.
- Соседние строки над порогом объединяются в один эпизод. id = `ml|<predict|fraud>|<vehicleId>|<параметр>|<начало эпизода>`,
  поэтому повторные сканы (окно 3 ч каждые 15 мин) не плодят дубликаты.

## Как подключить модель

1. Положить артефакт в `models/predictive.*` или `models/antifraud.*`.
2. Добавить рантайм (onnxruntime / scikit-learn / lightgbm) в `pyproject.toml`.
3. Реализовать загрузку в `models/registry.py::load_model` — объект с интерфейсом `ScoringModel`
   (`version`, `expected_features`, `score(FeatureMatrix) -> оценки 0..1 по строкам`).
