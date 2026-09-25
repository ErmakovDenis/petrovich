"""Загрузка моделей из каталога PA_MODELS_DIR при старте сервиса."""

import logging
from enum import Enum
from pathlib import Path

from .base import ScoringModel

log = logging.getLogger(__name__)


class ModelName(str, Enum):
    # Предиктивная аналитика: риск отказа узлов (двигатель, АКБ, охлаждение) в ближайшем горизонте.
    PREDICTIVE = "predictive"
    # Антифрод: сливы и фиктивные заправки топлива, накрутка пробега/моточасов.
    ANTIFRAUD = "antifraud"


class ModelRegistry:
    def __init__(self, models_dir: Path):
        self.models_dir = models_dir
        self._models: dict[ModelName, ScoringModel] = {}

    def load_all(self) -> None:
        for name in ModelName:
            model = load_model(self.models_dir, name)
            if model is None:
                log.warning("Модель %s не найдена в %s — эндпоинт будет отвечать ready=false", name.value, self.models_dir)
            else:
                self._models[name] = model
                log.info("Модель %s загружена, версия %s", name.value, model.version)

    def get(self, name: ModelName) -> ScoringModel | None:
        return self._models.get(name)

    def register(self, name: ModelName, model: ScoringModel) -> None:
        """Подменить модель (тесты, горячая перезагрузка)."""
        self._models[name] = model


def load_model(models_dir: Path, name: ModelName) -> ScoringModel | None:
    """МЕСТО ПОД ЗАГРУЗКУ МОДЕЛИ.

    Формат артефакта ещё не выбран. Когда появится обученная модель:
      1. Положить её в models/<name>.<ext> (например, antifraud.onnx или predictive.joblib).
      2. Добавить зависимость рантайма (onnxruntime / scikit-learn / lightgbm) в pyproject.toml.
      3. Обернуть в класс с интерфейсом ScoringModel (models/base.py) и вернуть отсюда.
    Пока модели нет — возвращаем None, сервис работает и отвечает ready=false.
    """
    candidates = sorted(models_dir.glob(f"{name.value}.*")) if models_dir.is_dir() else []
    if not candidates:
        return None
    log.warning("Найден %s, но загрузчик для этого формата ещё не реализован", candidates[0].name)
    return None
