"""Список получателей писем — файл конфигурации стенда (FS_EMAIL_RECIPIENTS_PATH, TOML, образец —
recipients.example.toml). Адресовать письмо можно только им: модель видит id, имя и роль, но не адрес, и указывает
получателей только по id.

    [[recipient]]
    id = "mechanic"             # латиница, цифры, «-», «_»
    name = "Иван Петров"
    role = "Механик"
    email = "mechanic@example.org"
    schemas = ["12345"]         # необязательно: только для этих схем AutoGRAPH; нет — для всех
"""

import re
import tomllib
from dataclasses import dataclass
from pathlib import Path

ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
# Проверка формы адреса, а не доставляемости: без пробелов, одна «@», домен с точкой.
EMAIL = re.compile(r"^[^@\s<>,;\"]+@[^@\s<>,;\"]+\.[^@\s<>,;\"]+$")


class RecipientsError(ValueError):
    pass


@dataclass(frozen=True)
class Recipient:
    id: str
    name: str
    role: str
    email: str
    # Пусто — получатель доступен во всех схемах.
    schemas: frozenset[str] = frozenset()

    def allowed_in(self, schema_id: str) -> bool:
        return not self.schemas or schema_id in self.schemas


class Recipients:
    def __init__(self, items: list[Recipient]):
        self._by_id = {r.id: r for r in items}

    @classmethod
    def load(cls, path: Path) -> "Recipients":
        """Читается при старте стенда: ошибка в файле — ошибка запуска с указанием места."""
        try:
            data = tomllib.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError, tomllib.TOMLDecodeError) as e:
            raise RecipientsError(f"файл получателей {path}: {e}") from e
        raw = data.get("recipient", [])
        if not isinstance(raw, list):
            raise RecipientsError(f"файл получателей {path}: ожидается [[recipient]]")
        items: list[Recipient] = []
        for n, r in enumerate(raw, 1):
            where = f"файл получателей {path}, получатель №{n}"
            if not isinstance(r, dict):
                raise RecipientsError(f"{where}: ожидается таблица")
            unknown = set(r) - {"id", "name", "role", "email", "schemas"}
            if unknown:
                raise RecipientsError(f"{where}: неизвестные поля {sorted(unknown)}")
            values = {k: r.get(k) for k in ("id", "name", "role", "email")}
            for key, value in values.items():
                if not isinstance(value, str) or not value.strip():
                    raise RecipientsError(f"{where}: не задано поле {key}")
            if not ID.match(values["id"]):
                raise RecipientsError(f"{where}: id — латиница, цифры, «-» и «_», до 64 символов")
            if not EMAIL.match(values["email"].strip()):
                raise RecipientsError(f"{where}: неверный адрес")
            schemas = r.get("schemas", [])
            if not isinstance(schemas, list) or not all(isinstance(s, str | int) for s in schemas):
                raise RecipientsError(f"{where}: schemas — список id схем")
            if any(i.id == values["id"] for i in items):
                raise RecipientsError(f"{where}: id {values['id']} повторяется")
            items.append(Recipient(values["id"], values["name"].strip(), values["role"].strip(),
                                   values["email"].strip(), frozenset(str(s) for s in schemas)))
        return cls(items)

    def for_schema(self, schema_id: str) -> list[Recipient]:
        return [r for r in self._by_id.values() if r.allowed_in(schema_id)]

    def get(self, recipient_id: str, schema_id: str) -> Recipient | None:
        r = self._by_id.get(recipient_id)
        return r if r is not None and r.allowed_in(schema_id) else None

    def __len__(self) -> int:
        return len(self._by_id)
