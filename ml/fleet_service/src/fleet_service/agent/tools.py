"""Tools ассистента: регистрация, описание для модели и исполнение.

Ошибки исполнения (неизвестный tool, невалидные аргументы, исключение в обработчике) не прерывают ответ: они
возвращаются модели как `{"error": ...}`, и модель сообщает пользователю, что данных нет.
"""

import json
import logging
import time
from dataclasses import dataclass
from typing import Any, Awaitable, Callable

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ToolContext:
    """От чьего имени вызывается tool: токен сессии AutoGRAPH, схема и смещение пояса пользователя."""

    session: str
    schema_id: str
    utc_offset_minutes: int


ToolHandler = Callable[[dict[str, Any], ToolContext], Awaitable[Any]]


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    # JSON Schema аргументов (type: object).
    parameters: dict[str, Any]
    handler: ToolHandler


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}

    def register(self, tool: Tool) -> None:
        if tool.name in self._tools:
            raise ValueError(f"tool {tool.name} уже зарегистрирован")
        self._tools[tool.name] = tool

    def __len__(self) -> int:
        return len(self._tools)

    def specs(self) -> list[dict[str, Any]]:
        """Описание tools в формате OpenAI `tools`."""
        return [
            {"type": "function", "function": {"name": t.name, "description": t.description, "parameters": t.parameters}}
            for t in self._tools.values()
        ]

    async def execute(self, name: str, raw_arguments: str | None, ctx: ToolContext) -> str:
        """Исполняет tool и возвращает результат JSON-строкой для сообщения role=tool."""
        tool = self._tools.get(name)
        if tool is None:
            return _error(f"неизвестный tool {name}")
        try:
            args = json.loads(raw_arguments or "{}")
        except ValueError:
            return _error("аргументы не в формате JSON")
        if not isinstance(args, dict):
            return _error("аргументы должны быть объектом JSON")
        start = time.monotonic()
        try:
            result = await tool.handler(args, ctx)
        except Exception as e:  # noqa: BLE001 — сбой tool не должен ронять ответ ассистента
            log.warning("tool %s: ошибка за %.0f мс: %s", name, (time.monotonic() - start) * 1000, e)
            return _error(f"{name} не выполнен: {e}")
        log.info("tool %s: выполнен за %.0f мс", name, (time.monotonic() - start) * 1000)
        return json.dumps(result, ensure_ascii=False, default=str)


def _error(message: str) -> str:
    return json.dumps({"error": message}, ensure_ascii=False)
