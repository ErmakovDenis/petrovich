"""Нагрузочный тест эндпоинта аналитики: ступенями поднимает число одновременных запросов и печатает throughput и
задержку (p50, p95) — по таблице выбирается PA_MAX_CONCURRENCY (README, «Нагрузка»).

    # против работающего сервиса (docker compose или uvicorn)
    .venv/bin/python scripts/load_test.py --url http://127.0.0.1:8001 [--api-key …]
    # сервис поднимается отдельным процессом с моделью-заглушкой: признаки считаются по-настоящему, оценка — нули
    .venv/bin/python scripts/load_test.py --stub-model

Вход по умолчанию — VehicleTelemetry за 7 дней по 15 мин из testdata/golden/trip-tables/synthetic-7d.case.json
(как его собирает приложение). Мерить нужно сам расчёт, без предела нагрузки сервиса: с --stub-model скрипт снимает
его сам (PA_MAX_CONCURRENCY=1000); работающий сервис для --url запускайте с PA_MAX_CONCURRENCY=1000 и
PA_QUEUE_MAX=1000, иначе таблица покажет очередь текущего предела, а не мощность. Колено — где throughput перестаёт
расти, а p95 резко растёт; PA_MAX_CONCURRENCY — около него.
"""

import argparse
import asyncio
import json
import os
import socket
import statistics
import subprocess
import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
GOLDEN = ROOT.parents[1] / "testdata" / "golden" / "trip-tables" / "synthetic-7d.case.json"


def stub_app():
    """Фабрика для uvicorn --factory: сервис с моделью-заглушкой у обоих видов аналитики."""
    import numpy as np

    from predictive_antifraud.config import Settings
    from predictive_antifraud.main import create_app
    from predictive_antifraud.models.registry import ModelName, ModelRegistry

    class StubModel:
        version = "load-test"
        expected_features = None

        def score(self, features):
            return np.zeros(len(features.timestamps))

    registry = ModelRegistry(Path(os.environ.get("PA_MODELS_DIR", "/nonexistent")))
    for name in ModelName:
        registry.register(name, StubModel())
    return create_app(Settings(_env_file=None), registry)


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _start_stub() -> tuple[subprocess.Popen, str]:
    port = _free_port()
    env = {**os.environ, "PYTHONPATH": f"{ROOT / 'src'}{os.pathsep}{Path(__file__).parent}",
           "PA_MAX_CONCURRENCY": "1000", "PA_QUEUE_MAX": "1000"}
    proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "--factory", "load_test:stub_app", "--port", str(port), "--log-level", "warning"],
        env=env,
    )
    url = f"http://127.0.0.1:{port}"
    for _ in range(100):
        try:
            if httpx.get(url + "/health", timeout=1).status_code == 200:
                return proc, url
        except httpx.HTTPError:
            pass
        time.sleep(0.1)
    proc.kill()
    raise SystemExit("сервис с моделью-заглушкой не запустился")


async def _level(url: str, path: str, payload: bytes, headers: dict, concurrency: int, duration: float) -> dict:
    latencies: list[float] = []
    codes: dict[int, int] = {}
    deadline = time.monotonic() + duration

    async def worker(client: httpx.AsyncClient) -> None:
        while time.monotonic() < deadline:
            started = time.monotonic()
            try:
                resp = await client.post(path, content=payload, headers=headers)
                code = resp.status_code
            except httpx.HTTPError:
                code = 0
            codes[code] = codes.get(code, 0) + 1
            if code == 200:
                latencies.append(time.monotonic() - started)

    limits = httpx.Limits(max_connections=concurrency, max_keepalive_connections=concurrency)
    started = time.monotonic()
    async with httpx.AsyncClient(base_url=url, timeout=60, limits=limits) as client:
        await asyncio.gather(*(worker(client) for _ in range(concurrency)))
    # Фактическое время: последние запросы ступени заканчиваются после срока.
    elapsed = time.monotonic() - started
    ordered = sorted(latencies)
    return {
        "concurrency": concurrency,
        "rps": len(latencies) / elapsed,
        "p50": statistics.median(ordered) * 1000 if ordered else float("nan"),
        "p95": ordered[int(0.95 * (len(ordered) - 1))] * 1000 if ordered else float("nan"),
        "errors": sum(n for c, n in codes.items() if c != 200),
    }


async def _run(args: argparse.Namespace, url: str) -> None:
    case = json.loads(Path(args.payload).read_text(encoding="utf-8"))
    payload = json.dumps(case.get("expected", case), ensure_ascii=False).encode()
    headers = {"Content-Type": "application/json"}
    if args.api_key:
        headers["X-API-Key"] = args.api_key
    rows = len(next(iter(case.get("expected", case)["tables"].values()))["timestamps"])
    if args.url:
        print("Внимание: у сервиса должен быть снят предел (PA_MAX_CONCURRENCY=1000, PA_QUEUE_MAX=1000), иначе "
              "задержка — это очередь предела, а ответы 503 — его отказы.\n")
    print(f"Вход: {Path(args.payload).name}, {rows} строк, {len(payload) // 1024} КБ; {args.path}; "
          f"{args.duration:g} с на ступень; процессор: {os.cpu_count()} ядер\n")
    print("| Одновременно | Запросов/с | p50, мс | p95, мс | Ошибок |")
    print("|---|---|---|---|---|")
    await _level(url, args.path, payload, headers, 1, 2)  # прогрев
    for c in args.levels:
        r = await _level(url, args.path, payload, headers, c, args.duration)
        print(f"| {r['concurrency']} | {r['rps']:.1f} | {r['p50']:.0f} | {r['p95']:.0f} | {r['errors']} |", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Нагрузочный тест predictive_antifraud")
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--url", help="адрес работающего сервиса")
    target.add_argument("--stub-model", action="store_true", help="поднять сервис с моделью-заглушкой")
    parser.add_argument("--path", default="/v1/antifraud/check")
    parser.add_argument("--api-key", default=os.environ.get("PA_API_KEY", ""))
    parser.add_argument("--payload", default=str(GOLDEN))
    parser.add_argument("--levels", default="1,2,4,8,16,32", type=lambda s: [int(x) for x in s.split(",")])
    parser.add_argument("--duration", default=10.0, type=float, help="секунд на ступень")
    args = parser.parse_args()
    proc = None
    url = args.url
    if args.stub_model:
        proc, url = _start_stub()
    try:
        asyncio.run(_run(args, url))
    finally:
        if proc is not None:
            proc.terminate()
            proc.wait(10)


if __name__ == "__main__":
    main()
