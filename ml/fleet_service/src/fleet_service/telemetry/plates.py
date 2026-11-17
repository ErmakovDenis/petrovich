"""Гос. номер из Properties AutoGRAPH или из имени машины («Урал NEXT А001АА66»)."""

from __future__ import annotations

import re
from typing import Any

_LETTERS = "АВЕКМНОРСТУХABEKMHOPCTYX"
# Регион (2–3 цифры) необязателен: в тестовых/API данных бывает «А001АА» без региона.
_IN_NAME = re.compile(
    rf"(?<![А-Яа-яA-Za-z0-9])([{_LETTERS}])\s*(\d{{3}})\s*([{_LETTERS}]{{2}})(?:\s*(\d{{2,3}}))?(?![А-Яа-яA-Za-z0-9])",
    re.IGNORECASE,
)
# Парк AutoGRAPH: «Урал NEXT (гн 8901)».
_GN_IN_NAME = re.compile(r"(?i)(?:^|[\s(])гн\s*[:.\-]?\s*(\d{3,6})(?=\s*[)]|$|[\s,;])")
# Спецтехника: «6049кх11».
_SPECIAL_IN_NAME = re.compile(
    rf"(?<![А-Яа-яA-Za-z0-9])(\d{{3,4}})\s*([{_LETTERS}]{{2}})\s*(\d{{2,3}})(?![А-Яа-яA-Za-z0-9])",
    re.IGNORECASE,
)
_DISPLAY_KEYS = {"displayname", "alias", "алиас"}
_LOOKALIKE = str.maketrans({
    "A": "А", "B": "В", "E": "Е", "K": "К", "M": "М", "H": "Н",
    "O": "О", "P": "Р", "C": "С", "T": "Т", "Y": "У", "X": "Х",
})
_PLATE_KEYS = {
    "госномер", "госномера", "госзнак", "госзнака", "госрегзнак", "гн",
    "регномер", "регистрационныйномер", "regnumber", "regnum",
    "vehicleregnumber", "vehicleregnum",
    "platenumber", "plateno", "numberplate", "licenseplate", "registrationnumber",
}


def normalize(plate: str) -> str:
    return plate.strip().upper().translate(_LOOKALIKE).replace(" ", "")


def from_name(name: str) -> str | None:
    m = _IN_NAME.search(name)
    if m:
        return normalize("".join(g for g in m.groups() if g))
    special = _SPECIAL_IN_NAME.search(name)
    if special:
        return normalize("".join(special.groups()))
    gn = _GN_IN_NAME.search(name)
    return gn.group(1) if gn else None


def _is_plate_property(name: str) -> bool:
    key = re.sub(r"[\s._\-]", "", name.lower())
    if key in _PLATE_KEYS:
        return True
    return "гос" in key and "ном" in key


def _property_value(raw: Any) -> str | None:
    if raw is None:
        return None
    if isinstance(raw, str):
        text = raw.strip()
        return text or None
    if isinstance(raw, (int, float)):
        return str(raw)
    return None


def _fallback_plate(value: str) -> str | None:
    """Normalize explicit plate fields; skip AutoGRAPH serials in VehicleRegNumber («2041254»)."""
    compact = value.replace(" ", "")
    if compact.isdigit():
        # Короткие «гн 8901»-подобные; длинные цифры — серийник прибора, не номер.
        return compact if 3 <= len(compact) <= 6 else None
    return normalize(value)


def from_properties(properties: list[dict[str, Any]] | None) -> str | None:
    props = [p for p in (properties or []) if isinstance(p, dict)]
    for prop in props:
        name = prop.get("Name")
        if not isinstance(name, str) or not _is_plate_property(name):
            continue
        value = _property_value(prop.get("Value"))
        if not value:
            continue
        plate = from_name(value) or _fallback_plate(value)
        if plate:
            return plate
    for prop in props:
        name = prop.get("Name")
        if not isinstance(name, str):
            continue
        key = re.sub(r"[\s._\-]", "", name.lower())
        if key not in _DISPLAY_KEYS:
            continue
        value = _property_value(prop.get("Value"))
        if not value:
            continue
        plate = from_name(value)
        if plate:
            return plate
    return None


def from_device(name: str | None, properties: list[dict[str, Any]] | None = None) -> str | None:
    return from_properties(properties) or (from_name(name) if name else None)
