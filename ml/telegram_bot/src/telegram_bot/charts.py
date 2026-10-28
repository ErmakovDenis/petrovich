"""«Откуда цифра» (макет 6): ряд параметра вокруг события — сводка, график PNG и таблица CSV.

Данные — VehicleTelemetry стенда (`GET /v1/telemetry`): таблицы по категориям, интервалы и значения как в приложении.
Ряд события — столбец с тем же параметром (parameterName аномалии).
"""

import csv
import io
from dataclasses import dataclass
from datetime import datetime

import matplotlib

matplotlib.use("Agg")  # без экрана: только в файл

import matplotlib.dates as mdates  # noqa: E402
import matplotlib.pyplot as plt  # noqa: E402

RED = "#d93025"
LINE = "#1a73e8"


@dataclass
class Series:
    name: str
    caption: str
    unit: str | None
    times: list[datetime]
    values: list[float | None]

    def points(self) -> list[tuple[datetime, float]]:
        return [(t, v) for t, v in zip(self.times, self.values, strict=True) if v is not None]

    def step_minutes(self) -> int | None:
        if len(self.times) < 2:
            return None
        return round((self.times[1] - self.times[0]).total_seconds() / 60)


def find_series(telemetry: dict, parameter: str) -> Series | None:
    """Столбец параметра [parameter] из VehicleTelemetry стенда."""
    for table in (telemetry.get("tables") or {}).values():
        for column in table.get("columns") or []:
            p = column["parameter"]
            if p["name"] == parameter:
                return Series(p["name"], p["caption"], p.get("unit"),
                              [datetime.fromisoformat(t) for t in table["timestamps"]], column["values"])
    return None


def number(v: float) -> str:
    return f"{v:.1f}".rstrip("0").rstrip(".").replace(".", ",").replace("-", "−")


def png(series: Series, event_time: datetime, title: str) -> bytes:
    """График ряда с красной точкой в начале события."""
    points = series.points()
    fig, ax = plt.subplots(figsize=(8, 4.2), dpi=120)
    try:
        ax.plot([t for t, _ in points], [v for _, v in points], color=LINE, linewidth=1.6)
        near = min(points, key=lambda p: abs((p[0] - event_time).total_seconds()))
        ax.scatter([near[0]], [near[1]], color=RED, s=60, zorder=3)
        unit = f" {series.unit}" if series.unit else ""
        ax.annotate(f"{event_time:%H:%M} {number(near[1])}{unit}", near, textcoords="offset points", xytext=(8, 8),
                    color=RED, fontsize=10, fontweight="bold")
        ax.set_title(title, fontsize=11, loc="left")
        ax.set_ylabel(series.unit or "")
        ax.grid(True, color="#e0e0e0", linewidth=0.8)
        ax.xaxis.set_major_formatter(mdates.DateFormatter("%H:%M"))
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        fig.tight_layout()
        out = io.BytesIO()
        fig.savefig(out, format="png")
        return out.getvalue()
    finally:
        plt.close(fig)


def csv_bytes(series: Series) -> bytes:
    """Ряд таблицей: время и значение. «;» и запятая в числах — Excel в русской локали откроет как есть."""
    out = io.StringIO()
    w = csv.writer(out, delimiter=";")
    w.writerow(["Время", f"{series.caption}{', ' + series.unit if series.unit else ''}"])
    for t, v in zip(series.times, series.values, strict=True):
        w.writerow([f"{t:%d.%m.%Y %H:%M}", "" if v is None else f"{v}".replace(".", ",")])
    # BOM — чтобы Excel узнал UTF-8 (кириллица в заголовках).
    return out.getvalue().encode("utf-8-sig")
