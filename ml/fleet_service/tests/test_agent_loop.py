import json

import pytest

from fleet_service.agent.llm import LLMError
from fleet_service.agent.loop import Agent, AgentIterationLimit
from fleet_service.agent.tools import Tool, ToolContext, ToolRegistry

from .conftest import FakeLLM, text, tool_call

CTX = ToolContext(session="valid-token", schema_id="schema-1", utc_offset_minutes=300)


def registry_with_sum(calls: list) -> ToolRegistry:
    async def add(args: dict, ctx: ToolContext) -> dict:
        calls.append((args, ctx))
        if args.get("a") == "boom":
            raise RuntimeError("датчик не ответил")
        return {"sum": args["a"] + args["b"]}

    registry = ToolRegistry()
    registry.register(Tool(
        name="add",
        description="Сложить два числа",
        parameters={"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
                    "required": ["a", "b"]},
        handler=add,
    ))
    return registry


@pytest.mark.anyio
async def test_tool_is_registered_called_and_result_returned_to_model():
    calls: list = []
    llm = FakeLLM(tool_call("c1", "add", '{"a": 2, "b": 3}'), text("Сумма — 5."))
    agent = Agent(llm, registry_with_sum(calls), max_iterations=3)

    result = await agent.run([{"role": "user", "content": "2+3?"}], CTX)

    assert result.reply == "Сумма — 5."
    assert result.tool_calls == ["add"] and result.iterations == 2
    assert calls == [({"a": 2, "b": 3}, CTX)]
    first_messages, tools = llm.calls[0]
    assert tools == [{"type": "function", "function": {
        "name": "add", "description": "Сложить два числа",
        "parameters": {"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
                       "required": ["a", "b"]}}}]
    second_messages, _ = llm.calls[1]
    assert second_messages[-2]["role"] == "assistant" and second_messages[-2]["tool_calls"][0]["id"] == "c1"
    assert second_messages[-1] == {"role": "tool", "tool_call_id": "c1", "content": json.dumps({"sum": 5})}


@pytest.mark.anyio
@pytest.mark.parametrize("name, arguments, error", [
    ("missing", "{}", "неизвестный tool missing"),
    ("add", "{не json", "аргументы не в формате JSON"),
    ("add", "[1, 2]", "аргументы должны быть объектом JSON"),
    ("add", '{"a": "boom", "b": 1}', "add не выполнен: датчик не ответил"),
])
async def test_tool_failures_go_back_to_model(name, arguments, error):
    llm = FakeLLM(tool_call("c1", name, arguments), text("Данных нет."))
    agent = Agent(llm, registry_with_sum([]), max_iterations=3)

    result = await agent.run([{"role": "user", "content": "?"}], CTX)

    assert result.reply == "Данных нет."
    tool_message = llm.calls[1][0][-1]
    assert tool_message["role"] == "tool" and json.loads(tool_message["content"]) == {"error": error}


@pytest.mark.anyio
async def test_iteration_limit():
    llm = FakeLLM(*[tool_call(f"c{i}", "add", '{"a": 1, "b": 1}') for i in range(5)])
    agent = Agent(llm, registry_with_sum([]), max_iterations=3)

    with pytest.raises(AgentIterationLimit) as e:
        await agent.run([{"role": "user", "content": "?"}], CTX)
    assert e.value.limit == 3 and len(llm.calls) == 3
    assert "не уложился в 3" in str(e.value)


@pytest.mark.anyio
async def test_empty_registry_sends_no_tools_and_empty_reply_is_error():
    llm = FakeLLM(text("  "))
    with pytest.raises(LLMError):
        await Agent(llm, ToolRegistry(), max_iterations=2).run([{"role": "user", "content": "?"}], CTX)
    assert llm.calls[0][1] == []


@pytest.mark.anyio
async def test_content_as_parts():
    llm = FakeLLM({"role": "assistant", "content": [{"type": "text", "text": "Да"}, {"type": "text", "text": "."}]})
    result = await Agent(llm, ToolRegistry(), max_iterations=1).run([{"role": "user", "content": "?"}], CTX)
    assert result.reply == "Да."


def test_duplicate_tool_rejected():
    registry = registry_with_sum([])
    with pytest.raises(ValueError):
        registry.register(Tool("add", "", {"type": "object"}, registry_with_sum([])._tools["add"].handler))
