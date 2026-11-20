"""Строгий разбор прав на схемы: null, bool и объекты нельзя превращать в строковые полномочия."""


def parse_schema_ids(schemas: list) -> set[str]:
    result = set()
    for schema in schemas:
        value = schema.get("ID") if isinstance(schema, dict) else None
        if isinstance(value, bool) or not isinstance(value, (str, int)):
            continue
        value = str(value)
        if value and value == value.strip() and not any(ord(c) < 32 or ord(c) == 127 for c in value):
            result.add(value)
    return result
