from typing import Protocol

import numpy as np

from ..features.extractor import FeatureMatrix


class ScoringModel(Protocol):
    """Модель получает матрицу признаков и возвращает для каждой строки оценку аномальности 0..1."""

    version: str
    # Ожидаемый порядок признаков (имена параметров API); None — любой.
    expected_features: list[str] | None

    def score(self, features: FeatureMatrix) -> np.ndarray: ...
