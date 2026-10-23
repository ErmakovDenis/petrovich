"""Проверка модели на tools ассистента перед тем, как ставить её запасной (FS_LLM_FALLBACK_MODELS) или резервной.

    FS_EVAL_API_KEY=... python -m fleet_service.agent.eval_tools --base-url https://openrouter.ai/api/v1 \\
        --model openai/gpt-4o-mini [--model другая/модель …] [--repeat 3]

Набор — eval_cases.jsonl рядом с модулем: вопрос из реальных сценариев чата, при необходимости — уже полученный список машин
(`fleet`) или карточка аномалии (`anomaly`), и ожидание: какой tool модель вызовет первым (null — ответит текстом)
и ключевые аргументы (строка с ^ — регулярное выражение, иначе точное совпадение). Текущее время пользователя
фиксировано (2026-09-17 10:00, UTC+05:00), чтобы «вчера» и «за неделю» проверялись однозначно.

Модель допускается, если доля верных вызовов (tool и аргументы) не ниже, чем у основной, с оговорённым допуском
(например −5 п. п.). Прогон на настоящих моделях — расход кредитов: запускает владелец стенда.
"""

import argparse
import asyncio
import json
import os
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

from ..config import Settings
from ..schemas.chat import ChatRequest
from .prompt import build_messages, load_system_prompt
from .llm import Message, provider_preferences

# Данные пакета: набор есть и в образе стенда (docker compose exec fleet_service python -m …).
CASES = Path(__file__).resolve().parent / "eval_cases.jsonl"
NOW = datetime(2026, 9, 17, 5, 0, tzinfo=timezone.utc)
UTC_OFFSET_MINUTES = 300
FLEET = {
    "total": 3,
    "vehicles": [
        {"id": "veh-1", "name": "FAW №1", "group": "Колонна 1"},
        {"id": "veh-2", "name": "Урал NEXT А001АА", "group": "Колонна 1"},
        {"id": "veh-3", "name": "FAW №2", "group": "Колонна 2"},
    ],
    "truncated": False,
}


@dataclass
class Outcome:
    case_id: str
    tool_ok: bool
    args_ok: bool
    got: str


def load_cases(path: Path = CASES) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def tool_specs(settings: Settings) -> list[dict[str, Any]]:
    """Tools, которые модель видит в чате (без писем: они включаются отдельным разрешением)."""
    from ..main import build_tools  # здесь, а не в начале: main импортирует весь стенд

    return build_tools(settings, None, None, None).specs()  # type: ignore[arg-type] — обработчики не вызываются


def case_messages(system_prompt: str, case: dict[str, Any]) -> list[Message]:
    request = ChatRequest.model_validate({
        "messages": [{"role": "user", "content": case["question"]}], "utcOffsetMinutes": UTC_OFFSET_MINUTES,
    })
    messages = build_messages(system_prompt, request, now=NOW, stored_anomaly=case.get("anomaly"))
    if case.get("fleet"):
        messages += [
            {"role": "assistant", "content": None, "tool_calls": [
                {"id": "call-0", "type": "function", "function": {"name": "list_vehicles", "arguments": "{}"}}]},
            {"role": "tool", "tool_call_id": "call-0", "content": json.dumps(FLEET, ensure_ascii=False)},
        ]
    return messages


def _matches(expected: Any, actual: Any) -> bool:
    if isinstance(expected, str) and expected.startswith("^"):
        return isinstance(actual, str) and re.match(expected, actual) is not None
    if isinstance(expected, list):
        return isinstance(actual, list) and sorted(map(str, actual)) == sorted(map(str, expected))
    return expected == actual


def score(case: dict[str, Any], message: Message) -> Outcome:
    expect = case["expect"]
    tools = expect["tool"] if isinstance(expect["tool"], list) else [expect["tool"]]
    calls = message.get("tool_calls") or []
    fn = (calls[0].get("function") or {}) if calls else {}
    name = fn.get("name") if calls else None
    try:
        args = json.loads(fn.get("arguments") or "{}") if calls else {}
    except ValueError:
        args = None
    tool_ok = name in tools
    args_ok = tool_ok and isinstance(args, dict) and all(
        _matches(v, args.get(k)) for k, v in (expect.get("args") or {}).items()
    )
    got = "текст" if name is None else f"{name}({json.dumps(args, ensure_ascii=False) if args is not None else 'не JSON'})"
    return Outcome(case["id"], tool_ok, args_ok, got)


async def evaluate(
    http: httpx.AsyncClient, base_url: str, model: str, api_key: str, cases: list[dict[str, Any]],
    settings: Settings, repeat: int = 1,
) -> list[Outcome]:
    system_prompt = load_system_prompt(settings)
    specs = tool_specs(settings)
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    outcomes = []
    for case in cases:
        for _ in range(repeat):
            body: dict[str, Any] = {
                "model": model, "messages": case_messages(system_prompt, case), "tools": specs,
                "temperature": settings.llm_temperature, "max_tokens": settings.llm_max_tokens,
            }
            if "openrouter" in base_url:
                # Та же маршрутизация, что у стенда (data_collection, sort): модель проверяется на тех же провайдерах.
                body["provider"] = provider_preferences(settings)
            try:
                resp = await http.post(base_url.rstrip("/") + "/chat/completions", json=body, headers=headers,
                                       timeout=settings.llm_timeout_seconds)
                resp.raise_for_status()
                message = resp.json()["choices"][0]["message"]
            except (httpx.HTTPError, ValueError, KeyError, IndexError) as e:
                outcomes.append(Outcome(case["id"], False, False, f"ошибка: {type(e).__name__}"))
                continue
            outcomes.append(score(case, message))
    return outcomes


def report(model: str, outcomes: list[Outcome]) -> str:
    n = len(outcomes) or 1
    tools = sum(o.tool_ok for o in outcomes)
    full = sum(o.args_ok for o in outcomes)
    lines = [f"## {model}", "", "| Вопрос | tool | аргументы | Ответ модели |", "|---|---|---|---|"]
    lines += [f"| {o.case_id} | {'✅' if o.tool_ok else '❌'} | {'✅' if o.args_ok else '❌'} | {o.got[:120]} |"
              for o in outcomes]
    lines += ["", f"Верный tool: {tools}/{len(outcomes)} ({100 * tools / n:.0f} %), "
              f"tool и аргументы: {full}/{len(outcomes)} ({100 * full / n:.0f} %)", ""]
    return "\n".join(lines)


async def _main(args: argparse.Namespace) -> None:
    # Настройки стенда (.env и окружение): температура, лимит токенов, маршрутизация провайдеров — как в чате.
    settings = Settings()
    cases = load_cases(Path(args.cases))
    key = os.environ.get(args.api_key_env, "")
    async with httpx.AsyncClient() as http:
        for model in args.model:
            print(report(model, await evaluate(http, args.base_url, model, key, cases, settings, args.repeat)))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Проверка модели на tools ассистента")
    parser.add_argument("--base-url", required=True, help="OpenAI-совместимый адрес, например https://openrouter.ai/api/v1")
    parser.add_argument("--model", action="append", required=True, help="модель; можно несколько раз")
    parser.add_argument("--api-key-env", default="FS_EVAL_API_KEY", help="переменная окружения с ключом")
    parser.add_argument("--cases", default=str(CASES))
    parser.add_argument("--repeat", type=int, default=1, help="сколько раз задавать каждый вопрос")
    asyncio.run(_main(parser.parse_args(argv)))


if __name__ == "__main__":
    main(sys.argv[1:])
