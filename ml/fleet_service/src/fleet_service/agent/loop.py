"""Цикл агента: модель → вызовы tools → модель … → текст ответа, не больше заданного числа обращений к модели."""

import logging
from dataclasses import dataclass, field

from .llm import LLMClient, LLMError, Message
from .tools import ToolContext, ToolRegistry

log = logging.getLogger(__name__)


class AgentIterationLimit(Exception):
    def __init__(self, limit: int):
        super().__init__(f"Ассистент не уложился в {limit} обращений к модели. Попробуйте задать вопрос проще.")
        self.limit = limit


@dataclass
class AgentResult:
    reply: str
    # Имена вызванных tools по порядку.
    tool_calls: list[str] = field(default_factory=list)
    # Сколько раз обращались к модели.
    iterations: int = 0


class Agent:
    def __init__(self, llm: LLMClient, tools: ToolRegistry, max_iterations: int):
        self._llm = llm
        self._tools = tools
        self._max_iterations = max_iterations

    async def run(self, messages: list[Message], ctx: ToolContext) -> AgentResult:
        """[messages] дополняется ответами модели и результатами tools."""
        specs = self._tools.specs(ctx.features)
        called: list[str] = []
        for iteration in range(1, self._max_iterations + 1):
            message = await self._llm.complete(messages, specs)
            tool_calls = message.get("tool_calls") or []
            if not tool_calls:
                reply = _text(message.get("content")).strip()
                if not reply:
                    raise LLMError("Модель вернула пустой ответ")
                return AgentResult(reply, called, iteration)

            messages.append({"role": "assistant", "content": message.get("content"), "tool_calls": tool_calls})
            for call in tool_calls:
                fn = call.get("function") or {}
                name = str(fn.get("name"))
                # Аргументы tools — id машин и периоды, секретов в них нет; длинные обрезаются. У tools писем
                # аргументы (текст письма) в лог не пишутся.
                arguments = " ".join(str(fn.get("arguments") or "").split())
                if not self._tools.logs_arguments(name):
                    arguments = "аргументы не пишутся в лог"
                log.info("итерация %d: модель вызывает tool %s(%s)", iteration, name,
                         arguments if len(arguments) <= 200 else arguments[:200] + "…")
                called.append(name)
                result = await self._tools.execute(name, fn.get("arguments"), ctx)
                messages.append({"role": "tool", "tool_call_id": call.get("id"), "content": result})
        raise AgentIterationLimit(self._max_iterations)


def _text(content: object) -> str:
    """content бывает строкой или списком частей [{type: text, text: ...}]."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(p.get("text", "") for p in content if isinstance(p, dict))
    return ""
