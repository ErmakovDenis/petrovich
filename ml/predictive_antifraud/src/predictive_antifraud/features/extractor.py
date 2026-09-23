"""Матрица признаков «время × параметры» — Python-аналог FeatureExtractor.kt.

Отличия от версии в приложении:
- таблицы разных категорий выравниваются по объединению отметок времени, а не по первой таблице;
- при выключенном зажигании CAN-параметры считаются пропусками (нули и «замёрзшие» обороты — не данные);
- рядом с матрицей возвращается маска наблюдённых значений, чтобы модель отличала пропуск от нуля.
"""

from dataclasses import dataclass
from datetime import datetime

import numpy as np

from ..schemas.telemetry import ParameterInfo, VehicleTelemetry
from . import parameters as P


@dataclass(frozen=True)
class FeatureMatrix:
    timestamps: list[datetime]
    # Порядок столбцов (имена параметров API).
    feature_names: list[str]
    # float32 [время, параметр]; пропуски заполнены последним известным значением (или 0).
    rows: np.ndarray
    # bool [время, параметр]; True — значение реально пришло в эту отметку времени.
    observed: np.ndarray
    # bool [время]; None — в данных нет параметра зажигания.
    ignition_on: np.ndarray | None
    parameters: dict[str, ParameterInfo]

    def reindex(self, names: list[str]) -> "FeatureMatrix":
        """Приводит столбцы к порядку, ожидаемому моделью; отсутствующие (например, BattaryVOLTAGE у FAW) — нули."""
        n = len(self.timestamps)
        rows = np.zeros((n, len(names)), dtype=np.float32)
        observed = np.zeros((n, len(names)), dtype=bool)
        index = {name: j for j, name in enumerate(self.feature_names)}
        for k, name in enumerate(names):
            j = index.get(name)
            if j is not None:
                rows[:, k] = self.rows[:, j]
                observed[:, k] = self.observed[:, j]
        return FeatureMatrix(self.timestamps, list(names), rows, observed, self.ignition_on, self.parameters)


def extract(telemetry: VehicleTelemetry) -> FeatureMatrix:
    timestamps = sorted({t for table in telemetry.tables.values() for t in table.timestamps})
    position = {t: i for i, t in enumerate(timestamps)}
    n = len(timestamps)

    names: list[str] = []
    infos: dict[str, ParameterInfo] = {}
    raw: list[np.ndarray] = []
    for table in telemetry.tables.values():
        idx = np.fromiter((position[t] for t in table.timestamps), dtype=np.int64, count=len(table.timestamps))
        for column in table.columns:
            name = column.parameter.name
            if name in infos:
                continue
            values = np.full(n, np.nan, dtype=np.float64)
            values[idx] = [np.nan if v is None else v for v in column.values]
            names.append(name)
            infos[name] = column.parameter
            raw.append(values)

    matrix = np.stack(raw, axis=1) if raw else np.empty((n, 0))
    ignition_on = _ignition(matrix, names)
    if ignition_on is not None:
        for j, name in enumerate(names):
            if name in P.CAN_PARAMETERS:
                matrix[~ignition_on, j] = np.nan

    observed = ~np.isnan(matrix)
    return FeatureMatrix(
        timestamps=timestamps,
        feature_names=names,
        rows=_forward_fill(matrix).astype(np.float32),
        observed=observed,
        ignition_on=ignition_on,
        parameters=infos,
    )


def _ignition(matrix: np.ndarray, names: list[str]) -> np.ndarray | None:
    for name in (P.IGNITION, P.IGNITION_CAN):
        if name in names:
            column = _forward_fill(matrix[:, [names.index(name)]])[:, 0]
            return column >= 0.5
    return None


def _forward_fill(matrix: np.ndarray) -> np.ndarray:
    """Заполняет NaN последним известным значением по столбцу; до первого значения — 0."""
    if matrix.size == 0:
        return matrix.copy()
    mask = np.isnan(matrix)
    idx = np.where(~mask, np.arange(matrix.shape[0])[:, None], 0)
    np.maximum.accumulate(idx, axis=0, out=idx)
    filled = matrix[idx, np.arange(matrix.shape[1])]
    return np.nan_to_num(filled, nan=0.0)
