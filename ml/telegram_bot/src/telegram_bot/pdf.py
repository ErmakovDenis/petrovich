"""Отчёт по группе машин (макет 8, «Отчёт по группе») в PDF: сводка, таблица по машинам и список событий.

Рисуется matplotlib (как графики «Откуда цифра»): шрифт DejaVu Sans из его поставки знает кириллицу, отдельных
шрифтов и библиотек не нужно.
"""

import io
from datetime import datetime

from matplotlib.backends.backend_pdf import PdfPages
from matplotlib.figure import Figure

from .exports import RESOLUTION, SEVERITY

A4 = (8.27, 11.69)
EVENTS_PER_PAGE = 48
# Высота строки таблицы, доля страницы.
ROW = 0.0175
MARGIN = 0.06


def _page(title: str | None = None) -> Figure:
    fig = Figure(figsize=A4)
    if title:
        fig.text(MARGIN, 0.955, title, fontsize=15, fontweight="bold", va="top")
    return fig


def _table(fig: Figure, top: float, header: list[str], rows: list[list[str]], widths: list[float]) -> None:
    height = ROW * (max(len(rows), 1) + 1)
    ax = fig.add_axes((MARGIN, top - height, 1 - 2 * MARGIN, height))
    ax.axis("off")
    table = ax.table(cellText=rows or [[""] * len(header)], colLabels=header, colWidths=widths, loc="upper left",
                     cellLoc="left")
    table.auto_set_font_size(False)
    table.set_fontsize(8)
    for (r, _), cell in table.get_celld().items():
        cell.set_edgecolor("#dddddd")
        if r == 0:
            cell.set_text_props(fontweight="bold")
            cell.set_facecolor("#f1f3f4")


def _short(text: str, n: int) -> str:
    return text if len(text) <= n else text[:n - 1] + "…"


def group_report(title: str, subtitle: str, summary: list[str], vehicle_rows: list[list], items: list[dict]) -> bytes:
    """[vehicle_rows] — exports.vehicle_rows; [items] — события (StoredAnomaly), по времени."""
    out = io.BytesIO()
    with PdfPages(out, metadata={"Title": title, "Creator": "Петрович"}) as pdf:
        fig = _page(title)
        fig.text(MARGIN, 0.925, subtitle, fontsize=10, color="#5f6368", va="top")
        y = 0.89
        for line in summary:
            fig.text(MARGIN, y, line, fontsize=10, va="top", wrap=True)
            y -= 0.022
        y -= 0.015
        fig.text(MARGIN, y, "По машинам", fontsize=12, fontweight="bold", va="top")
        rows = [[_short(str(r[0]), 34), str(r[1]), str(r[2]), str(r[3]), _short(r[4] or "—", 34)] for r in vehicle_rows]
        _table(fig, y - 0.03, ["Машина", "Событий", "Критичных", "Ждут решения", "Чаще всего"], rows,
               [0.34, 0.1, 0.12, 0.14, 0.3])
        pdf.savefig(fig)
        for start in range(0, len(items), EVENTS_PER_PAGE):
            chunk = items[start:start + EVENTS_PER_PAGE]
            fig = _page("События" if start == 0 else None)
            rows = [[f"{datetime.fromisoformat(a['eventTime']):%d.%m %H:%M}", _short(a["vehicleName"], 28),
                     _short(a["title"], 34), SEVERITY.get(a["severity"], a["severity"]),
                     RESOLUTION.get(a.get("resolution") or "", "ждёт решения")] for a in chunk]
            _table(fig, 0.91 if start == 0 else 0.95, ["Начало", "Машина", "Событие", "Важность", "Решение"], rows,
                   [0.13, 0.27, 0.3, 0.14, 0.16])
            pdf.savefig(fig)
    return out.getvalue()
