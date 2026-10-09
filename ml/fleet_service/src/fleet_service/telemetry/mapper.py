"""Ответы GetTripTables → таблицы с равномерным шагом. Порт `data/TripTablesMapper.kt` приложения.

AutoGRAPH отдаёт точку каждые ~10 с (с повторами времени) — сотни тысяч значений в сутки. Ответ разбирается
потоково (push-парсер ijson, байты подаются по мере чтения из сети) и сразу сворачивается в интервалы, без
построения дерева JSON. Сетка интервалов, способы свёртки, округление, фильтр пустых датчиков и удаление
дубликатов повторяют Kotlin; совпадение проверяют эталоны `testdata/golden/trip-tables` (tests/test_golden.py).
"""

import math
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta

import ijson

from ..autograph.errors import TripTablesTooLarge
from ..schemas.contract import CategoryTable, MetricCategory, ParameterColumn, ParameterInfo, Vehicle, VehicleTelemetry
from .parameters import STATUS_PARAMETERS, Aggregation

# Максимум точек в одном треке ответа по умолчанию (FS_TRIP_TABLES_MAX_POINTS). Реально ~2 200 за 6 ч.
MAX_POINTS = 500_000


def bucket_for(period: timedelta) -> timedelta:
    if period <= timedelta(hours=6):
        return timedelta(minutes=1)
    if period <= timedelta(hours=24):
        return timedelta(minutes=2)
    if period <= timedelta(days=3):
        return timedelta(minutes=5)
    return timedelta(minutes=15)


def _days_from_civil(y: int, m: int, d: int) -> int:
    """Дни от 1970-01-01 (пролептический григорианский календарь, как LocalDate.toEpochDay; год 0 допустим)."""
    y -= m <= 2
    era = (y if y >= 0 else y - 399) // 400
    yoe = y - era * 400
    doy = (153 * (m + (-3 if m > 2 else 9)) + 2) // 5 + d - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


def _month_length(y: int, m: int) -> int:
    if m == 2:
        return 29 if (y % 4 == 0 and y % 100 != 0) or y % 400 == 0 else 28
    return 30 if m in (4, 6, 9, 11) else 31


def epoch_second(s: str) -> int | None:
    """«yyyy-MM-ddTHH:mm:ss…» → секунды от эпохи (время считается UTC-меткой, хвост после секунд не важен).
    None, если строка в другом формате, — как fastEpochSecond/parseDateTime в Kotlin."""
    if len(s) < 19 or s[4] != "-" or s[7] != "-" or s[10] not in "Tt" or s[13] != ":" or s[16] != ":":
        return None
    parts = (s[0:4], s[5:7], s[8:10], s[11:13], s[14:16], s[17:19])
    if not all(p.isascii() and p.isdigit() for p in parts):
        return None
    year, month, day, hour, minute, second = (int(p) for p in parts)
    if not (1 <= month <= 12 and 1 <= day <= 31 and hour <= 23 and minute <= 59 and second <= 59):
        return None
    if day > 28 and day > _month_length(year, month):
        return None
    return _days_from_civil(year, month, day) * 86_400 + hour * 3_600 + minute * 60 + second


def _to_epoch(dt: datetime) -> int:
    return _days_from_civil(dt.year, dt.month, dt.day) * 86_400 + dt.hour * 3_600 + dt.minute * 60 + dt.second


# Грамматика Double.parseDouble без шестнадцатеричной формы: знак, NaN/Infinity, мантисса, порядок, суффикс f/d.
_JAVA_DOUBLE = re.compile(r"[+-]?(?:NaN|Infinity|(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?[fFdD]?)")


def parse_number_string(s: str) -> float:
    """Числовая строка → число; иначе NaN (строки-интервалы «00:00:10», текст). Как readNumber в Kotlin."""
    if not s or not (s[0].isdigit() or s[0] == "-") or ":" in s:
        return math.nan
    t = s.replace(",", ".").strip(" \t\n\r\x0b\x0c\x00")  # parseDouble обрезает пробельные символы
    if not _JAVA_DOUBLE.fullmatch(t):
        return math.nan
    if t[-1] in "fFdD" and not t.endswith("Infinity"):
        t = t[:-1]
    return float(t)


_LONG_MAX = 2**63 - 1
_LONG_MIN = -(2**63)


def java_round(x: float) -> float:
    """Math.round(x) (половина — вверх) как число: floor(x) и +1, если дробная часть не меньше 0,5.
    Сбойные значения — как в Java: NaN → 0, ±∞ и выход за long — границы long."""
    if math.isnan(x):
        return 0.0
    if x >= _LONG_MAX:
        return float(_LONG_MAX)
    if x <= _LONG_MIN:
        return float(_LONG_MIN)
    r = math.floor(x)
    return float(r + 1 if x - r >= 0.5 else r)


class _Accumulator:
    __slots__ = ("mode", "zero_is_missing", "sum", "n")

    def __init__(self, size: int, mode: Aggregation):
        self.mode = mode
        self.zero_is_missing = mode.zero_is_missing
        self.sum = [0.0] * size
        self.n = [0] * size

    def add(self, i: int, v: float) -> None:
        if self.zero_is_missing and v == 0.0:
            return
        mode = self.mode
        if mode is Aggregation.MEAN or mode is Aggregation.MEAN_NONZERO:
            self.sum[i] += v
        elif mode is Aggregation.MAX:
            self.sum[i] = v if self.n[i] == 0 else max(self.sum[i], v)
        else:  # MIN, MIN_NONZERO
            self.sum[i] = v if self.n[i] == 0 else min(self.sum[i], v)
        self.n[i] += 1

    def result(self) -> list[float | None]:
        mean = self.mode is Aggregation.MEAN or self.mode is Aggregation.MEAN_NONZERO
        out: list[float | None] = []
        for s, n in zip(self.sum, self.n):
            if n == 0:
                out.append(None)
            elif mean:
                out.append(java_round(s / n * 10) / 10.0)
            else:
                out.append(java_round(s * 10) / 10.0)
        return out


@dataclass
class BuildResult:
    telemetry: VehicleTelemetry
    # Скрытые столбцы (как в приложении), разделённые для tools: «нет данных» и «0» — разные вещи.
    # empty — за период ни одного значения; zero — значения были, но все 0 (машина стояла или датчик не подключён).
    empty: list[ParameterInfo] = field(default_factory=list)
    zero: list[ParameterInfo] = field(default_factory=list)
    bucket: timedelta = timedelta(minutes=1)


# Состояния разбора: где мы в структуре {dev: {Trips: [{DT: [...], Values: [{Name, Values: [...]}]}]}}.
_ROOT, _DEVICES, _DEVICE, _TRIPS, _TRIP, _DT, _COLUMNS, _COLUMN, _CVALUES, _DONE = range(10)
_STARTS = ("start_map", "start_array")
_ENDS = ("end_map", "end_array")


class _TripState:
    __slots__ = ("buckets", "pending")

    def __init__(self) -> None:
        # Индекс интервала для каждой точки; -1 — вне периода/не распознано.
        self.buckets: list[int] | None = None
        # Если «Values» встретится раньше «DT», значения буферизуются.
        self.pending: dict[str, list[float]] = {}


class TripTablesBuilder:
    """Накапливает значения из нескольких ответов (частей периода) в общую сетку интервалов."""

    def __init__(
        self,
        vehicle: Vehicle,
        from_: datetime,
        to: datetime,
        parameters: list[ParameterInfo],
        aggregation: dict[str, Aggregation],
        bucket: timedelta | None = None,
        max_points: int = MAX_POINTS,
    ):
        self.vehicle = vehicle
        self.from_ = from_
        self.to = to
        self.parameters = parameters
        self.bucket = bucket or bucket_for(to - from_)
        self.max_points = max_points
        self.start = from_.replace(second=0, microsecond=0)
        self._start_epoch = _to_epoch(self.start)
        self._bucket_seconds = int(self.bucket.total_seconds())
        self.count = (to - self.start) // timedelta(seconds=1) // self._bucket_seconds + 1
        self._acc = {p.name: _Accumulator(self.count, aggregation.get(p.name, Aggregation.MEAN)) for p in parameters}

    # --- разбор одного ответа ---

    def read_bytes(self, data: bytes) -> None:
        """Разбирает один ответ GetTripTables целиком (тесты, сохранённые ответы)."""
        reader = self.reader()
        reader.feed(data)
        reader.close()

    def reader(self) -> "_ResponseReader":
        """Потоковый разбор одного ответа: feed(байты) по мере чтения, close() в конце."""
        return _ResponseReader(self)

    def _bucket_index(self, dt: str) -> int:
        epoch = epoch_second(dt)
        if epoch is None:
            return -1
        idx = (epoch - self._start_epoch) // self._bucket_seconds
        return idx if 0 <= idx < self.count else -1

    def _add_column(self, name: str | None, values: list[float], buckets: list[int] | None,
                    pending: dict[str, list[float]]) -> None:
        acc = self._acc.get(name) if name is not None else None
        if acc is None or not values:
            return
        if buckets is not None:
            self._add(acc, buckets, values)
        else:
            pending[name] = values

    @staticmethod
    def _add(acc: _Accumulator, buckets: list[int], values: list[float]) -> None:
        # Значений больше, чем отметок времени, — лишние пропускаются; NaN — нет значения.
        for b, v in zip(buckets, values):
            if b >= 0 and v == v:
                acc.add(b, v)

    def _finish_trip(self, trip: _TripState) -> None:
        if trip.buckets is None:
            return
        for name, values in trip.pending.items():
            self._add(self._acc[name], trip.buckets, values)

    # --- результат ---

    def build(self) -> BuildResult:
        step = self.bucket
        timestamps = [self.start + step * i for i in range(self.count)]
        columns: list[ParameterColumn] = []
        empty: list[ParameterInfo] = []
        zero: list[ParameterInfo] = []
        seen: list[list[float | None]] = []
        for p in self.parameters:
            values = self._acc[p.name].result()
            # Отсутствующие / неподключённые датчики (всегда 0); состояния вроде «Зажигание» не скрываем.
            has_data = any(v is not None for v in values)
            if not ((p.name in STATUS_PARAMETERS and has_data) or any(v is not None and v != 0.0 for v in values)):
                (zero if has_data else empty).append(p)
                continue
            # В AutoGRAPH один и тот же ДУТ часто виден под несколькими именами (FL1 = FLTankMain = TankMainFuelLevel).
            if any(s == values for s in seen):
                continue
            seen.append(values)
            columns.append(ParameterColumn(parameter=p, values=values))

        by_category: dict[MetricCategory, list[ParameterColumn]] = {}
        for c in columns:
            by_category.setdefault(c.parameter.category, []).append(c)
        tables = {
            category: CategoryTable(category=category, timestamps=timestamps, columns=cols)
            for category, cols in by_category.items()
        }
        telemetry = VehicleTelemetry(vehicle=self.vehicle, from_=self.from_, to=self.to, tables=tables)
        return BuildResult(telemetry, empty, zero, self.bucket)


class _ResponseReader:
    """Конечный автомат над событиями ijson (start_map, map_key, number, …) для одного ответа GetTripTables."""

    def __init__(self, builder: TripTablesBuilder):
        self._b = builder
        self._events = ijson.sendable_list()
        self._parser = ijson.basic_parse_coro(self._events, use_float=True)
        self._stack = [_ROOT]
        self._key: list[str | None] = [None]
        self._skip = 0
        self._trip: _TripState | None = None
        self._dt: list[int] = []
        self._dt_last: tuple[str, int] = ("", -1)
        self._name: str | None = None
        self._values: list[float] | None = None

    def feed(self, chunk: bytes) -> None:
        self._parser.send(chunk)
        self._consume()

    def close(self) -> None:
        self._parser.close()
        self._consume()

    def _skip_value(self, event: str) -> None:
        if event in _STARTS:
            self._skip = 1

    def _push(self, state: int) -> None:
        self._stack.append(state)
        self._key.append(None)

    def _pop(self) -> None:
        self._stack.pop()
        self._key.pop()

    def _consume(self) -> None:
        events = self._events
        if not events:
            return
        b = self._b
        limit = b.max_points
        for event, value in events:
            if self._skip:
                if event in _STARTS:
                    self._skip += 1
                elif event in _ENDS:
                    self._skip -= 1
                continue
            state = self._stack[-1]

            if state == _CVALUES:  # самый частый случай — значения колонки
                if event == "end_array":
                    self._pop()
                    continue
                if len(self._values) >= limit:
                    raise TripTablesTooLarge(f"Слишком большой ответ сервера: более {limit} значений")
                if event == "number":
                    v = float(value)
                elif event == "boolean":
                    v = 1.0 if value else 0.0
                elif event == "string":
                    v = parse_number_string(value)
                else:
                    v = math.nan
                    self._skip_value(event)
                self._values.append(v)
                continue

            if state == _DT:
                if event == "end_array":
                    self._pop()
                    self._trip.buckets = self._dt
                    continue
                if len(self._dt) >= limit:
                    raise TripTablesTooLarge(f"Слишком большой ответ сервера: более {limit} точек")
                if event == "string":
                    # Время часто повторяется подряд — не разбираем одну и ту же строку заново.
                    last, idx = self._dt_last
                    if value != last:
                        idx = b._bucket_index(value)
                        self._dt_last = (value, idx)
                    self._dt.append(idx)
                else:
                    self._dt.append(-1)
                    self._skip_value(event)
                continue

            if event == "map_key":
                self._key[-1] = value
                continue
            if event == "end_map" or event == "end_array":
                self._close(state)
                continue

            key = self._key[-1]
            if state == _ROOT:
                if event == "start_map":
                    self._stack[-1] = _DONE
                    self._push(_DEVICES)
                else:
                    self._stack[-1] = _DONE
                    self._skip_value(event)
            elif state == _DEVICES:
                if event == "start_map":
                    self._push(_DEVICE)
                else:
                    self._skip_value(event)
            elif state == _DEVICE:
                if key == "Trips" and event == "start_array":
                    self._push(_TRIPS)
                else:
                    self._skip_value(event)
            elif state == _TRIPS:
                if event == "start_map":
                    self._trip = _TripState()
                    self._push(_TRIP)
                else:
                    self._skip_value(event)
            elif state == _TRIP:
                if key == "DT":
                    if event != "start_array":
                        raise ValueError("GetTripTables: DT — не массив")
                    self._dt = []
                    self._push(_DT)
                elif key == "Values":
                    if event != "start_array":
                        raise ValueError("GetTripTables: Values — не массив")
                    self._push(_COLUMNS)
                else:
                    self._skip_value(event)
            elif state == _COLUMNS:
                if event != "start_map":
                    raise ValueError("GetTripTables: колонка — не объект")
                self._name = None
                self._values = None
                self._push(_COLUMN)
            elif state == _COLUMN:
                if key == "Name":
                    if event == "string":
                        self._name = value
                    else:
                        self._name = None
                        self._skip_value(event)
                elif key == "Values" and event == "start_array":
                    self._values = []
                    self._push(_CVALUES)
                else:
                    self._skip_value(event)
            else:  # _DONE: после корневого значения ничего не читаем
                self._skip_value(event)
        del events[:]

    def _close(self, state: int) -> None:
        if state == _COLUMN:
            self._b._add_column(self._name, self._values or [], self._trip.buckets, self._trip.pending)
            self._values = None
        elif state == _TRIP:
            self._b._finish_trip(self._trip)
            self._trip = None
        self._pop()
