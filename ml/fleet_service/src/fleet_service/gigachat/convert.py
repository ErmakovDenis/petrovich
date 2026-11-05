"""Преобразование запроса и ответа между форматом OpenAI chat completions и API GigaChat.

Шлюз не хранит состояния: functions_state_id кладётся в id вызова функции, agent/loop.py передаёт tool_calls как есть,
и при следующем обращении id возвращается в шлюз. Чужой id (ответ резерва, история из проверки на tools) состояния
не несёт и передаётся без functions_state_id.
"""

import json
import uuid
from collections import defaultdict, deque
from typing import Any

# id вызова: с состоянием GigaChat и без него (модель его не прислала).
STATE_PREFIX = "gcs-"
NO_STATE_PREFIX = "gcn-"
# У GigaChat temperature строго больше нуля; FS_LLM_TEMPERATURE=0 значит «как можно меньше фантазии».
MIN_TEMPERATURE = 0.01


class InvalidRequest(ValueError):
    """Запрос нельзя преобразовать: ошибка вызывающей стороны (400)."""


def _no_constants(name: str) -> None:
    # NaN и Infinity json.loads принимает, а по стандарту JSON их нет: такой результат оборачивается как текст.
    raise ValueError(f"не JSON: {name}")


def _text(content: Any) -> str:
    """content бывает строкой, списком частей [{type: text, text: ...}] или пустым; у GigaChat он всегда строка."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(p.get("text", "") for p in content if isinstance(p, dict))
    return ""


def function_result(content: Any) -> str:
    """Результат функции: у GigaChat — JSON-объект в строке, остальное оборачивается в {"result": ...}."""
    text = _text(content)
    try:
        parsed = json.loads(text, parse_constant=_no_constants)
    except ValueError:
        parsed = text
    if isinstance(parsed, dict):
        return text
    return json.dumps({"result": parsed}, ensure_ascii=False)


def _arguments_for_gigachat(arguments: Any, fmt: str) -> Any:
    if isinstance(arguments, str):
        try:
            parsed = json.loads(arguments or "{}")
        except ValueError:
            parsed = {}
    else:
        parsed = arguments
    if not isinstance(parsed, dict):
        parsed = {}
    return json.dumps(parsed, ensure_ascii=False) if fmt == "string" else parsed


def _assistant_call(call: dict[str, Any], content: str, fmt: str) -> dict[str, Any]:
    fn = call.get("function") or {}
    message: dict[str, Any] = {
        "role": "assistant",
        "content": content,
        "function_call": {"name": fn.get("name"), "arguments": _arguments_for_gigachat(fn.get("arguments"), fmt)},
    }
    call_id = str(call.get("id") or "")
    if call_id.startswith(STATE_PREFIX):
        message["functions_state_id"] = call_id[len(STATE_PREFIX):]
    return message


def convert_messages(messages: list[dict[str, Any]], fmt: str) -> list[dict[str, Any]]:
    """Все system склеиваются в первое сообщение: иначе GigaChat отвечает 422. Несколько вызовов в одном ответе
    (бывают у резервной модели) разворачиваются в пары «вызов — результат»: GigaChat знает только один вызов за ответ."""
    system = [_text(m.get("content")) for m in messages if m.get("role") == "system"]
    converted: list[dict[str, Any]] = [{"role": "system", "content": "\n\n".join(system)}] if system else []
    # Результаты по id вызова, по порядку: резервная модель может повторять id, а числа не должны перепутаться.
    results: defaultdict[str, deque[int]] = defaultdict(deque)
    for i, m in enumerate(messages):
        if m.get("role") == "tool":
            results[str(m.get("tool_call_id"))].append(i)
    used: set[int] = set()
    for i, m in enumerate(messages):
        role = m.get("role")
        if role == "system":
            continue
        if role == "assistant" and m.get("tool_calls"):
            content = _text(m.get("content"))
            for call in m["tool_calls"]:
                converted.append(_assistant_call(call, content, fmt))
                content = ""
                pending = results[str(call.get("id"))]
                if pending:
                    index = pending.popleft()
                    used.add(index)
                    converted.append({"role": "function", "content": function_result(messages[index].get("content"))})
        elif role == "tool":
            # Результат без вызова в истории: передаём как есть, ответ о такой истории даст сам GigaChat.
            if i not in used:
                converted.append({"role": "function", "content": function_result(m.get("content"))})
        else:
            converted.append({"role": role, "content": _text(m.get("content"))})
    return converted


def convert_functions(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    functions = []
    for tool in tools:
        fn = tool.get("function") or {}
        item = {"name": fn.get("name"), "parameters": fn.get("parameters") or {"type": "object", "properties": {}}}
        if fn.get("description"):
            item["description"] = fn["description"]
        functions.append(item)
    return functions


def to_gigachat(body: dict[str, Any], default_model: str, fmt: str) -> dict[str, Any]:
    """Тело запроса к GigaChat. Только известные поля: лишнее (user, n и т. п.) API не ожидает."""
    messages = body.get("messages")
    if not isinstance(messages, list) or not messages or not all(isinstance(m, dict) for m in messages):
        raise InvalidRequest("Поле messages должно быть непустым списком сообщений")
    if body.get("stream"):
        raise InvalidRequest("Потоковые ответы шлюз не поддерживает")
    request: dict[str, Any] = {
        "model": body.get("model") or default_model,
        "messages": convert_messages(messages, fmt),
    }
    temperature = body.get("temperature")
    if isinstance(temperature, (int, float)):
        request["temperature"] = max(float(temperature), MIN_TEMPERATURE)
    if isinstance(body.get("max_tokens"), int):
        request["max_tokens"] = body["max_tokens"]
    if body.get("tools"):
        request["functions"] = convert_functions(body["tools"])
        # Значение по умолчанию спецификация не называет: модель должна сама решать, вызывать ли функцию.
        request["function_call"] = "auto"
    return request


def _tool_call(function_call: dict[str, Any], state_id: Any) -> dict[str, Any]:
    arguments = function_call.get("arguments")
    if not isinstance(arguments, str):
        # Спецификация противоречива: тип и примеры — объект, описание — строка. Принимаем оба вида.
        arguments = json.dumps(arguments if arguments is not None else {}, ensure_ascii=False)
    call_id = f"{STATE_PREFIX}{state_id}" if state_id else f"{NO_STATE_PREFIX}{uuid.uuid4().hex}"
    return {"id": call_id, "type": "function", "function": {"name": function_call.get("name"), "arguments": arguments}}


def from_gigachat(data: dict[str, Any], request_model: str) -> dict[str, Any]:
    """Ответ GigaChat в формате OpenAI: первый вариант, другие шлюз не запрашивает. Форму choices и message проверяет
    вызывающий код."""
    choice = data["choices"][0]
    source = choice["message"]
    message: dict[str, Any] = {"role": "assistant", "content": source.get("content") or None}
    finish = choice.get("finish_reason")
    if isinstance(source.get("function_call"), dict):
        message["tool_calls"] = [_tool_call(source["function_call"], source.get("functions_state_id"))]
        finish = "tool_calls"
    elif finish not in ("stop", "length"):
        # blacklist — это обычный ответ с текстом модели: о нём шлюз пишет в лог и считает его отдельно.
        finish = "stop"
    usage = data["usage"] if isinstance(data.get("usage"), dict) else {}
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex}",
        "object": "chat.completion",
        "created": data.get("created") or 0,
        "model": data.get("model") or request_model,
        "choices": [{"index": 0, "finish_reason": finish, "message": message}],
        "usage": {k: usage.get(k) or 0 for k in ("prompt_tokens", "completion_tokens", "total_tokens")},
    }
