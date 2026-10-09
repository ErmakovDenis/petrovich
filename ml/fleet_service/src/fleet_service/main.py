"""Точка входа: uvicorn --factory fleet_service.main:create_app

Приложение создаётся фабрикой, а не при импорте: импорт модуля в тестах не читает .env и переменные окружения.
Фоновая проверка (background/scheduler.py) идёт в том же процессе — запускайте стенд одним процессом uvicorn.
"""

import asyncio
import logging
from contextlib import asynccontextmanager, suppress
from datetime import timedelta

import httpx
from fastapi import FastAPI

from . import __version__
from .agent.fleet_tools import build_fleet_tools
from .agent.llm import LLMClient, OpenRouterClient
from .agent.loop import Agent
from .agent.prompt import load_system_prompt
from .agent.store_tools import build_store_tools
from .agent.tools import ToolRegistry
from .analytics.client import AnalyticsClient
from .api import anomalies as anomalies_api
from .api import background as background_api
from .api import chat, health
from .api import telemetry as telemetry_api
from .api.errors import install_error_handlers
from .autograph.client import AutoGraphClient
from .autograph.session import AutoGraphSessionChecker
from .background.access import AccessRepository
from .background.crypto import SecretBox
from .background.scheduler import BackgroundScanner
from .config import Settings, get_settings
from .log_masking import configure_logging
from .rules.check import AnomalyCheckService
from .store.db import create_db_engine
from .store.migrate import upgrade
from .store.repository import AnomalyRepository
from .store.scan import ScanService
from .telemetry.service import TelemetryService

log = logging.getLogger(__name__)


def build_tools(
    settings: Settings, telemetry: TelemetryService, checks: AnomalyCheckService, store: AnomalyRepository
) -> ToolRegistry:
    """Tools ассистента: телеметрия (шаг 2), проверка машины (шаг 3), хранилище аномалий и решений (шаг 4)."""
    registry = ToolRegistry(max_result_chars=settings.tool_max_result_chars)
    for tool in [*build_fleet_tools(settings, telemetry, checks), *build_store_tools(settings, telemetry, store)]:
        registry.register(tool)
    return registry


def build_store(settings: Settings) -> AnomalyRepository:
    """Хранилище: миграции схемы (FS_DB_AUTO_MIGRATE) и репозиторий на канонической сетке проверок."""
    engine = create_db_engine(settings.db_url)
    if settings.db_auto_migrate:
        upgrade(settings.db_url)
    return AnomalyRepository(
        engine,
        bucket=timedelta(minutes=settings.scan_bucket_minutes),
        drop_cooldown=timedelta(minutes=settings.rules.fuel_drop_cooldown_minutes),
        retention=timedelta(days=settings.store_retention_days),
    )


def create_app(
    settings: Settings | None = None,
    http: httpx.AsyncClient | None = None,
    llm: LLMClient | None = None,
    tools: ToolRegistry | None = None,
    background_loop: bool = True,
) -> FastAPI:
    """[http], [llm], [tools] подменяются в тестах подставными серверами и моделью; [background_loop] = False —
    фоновая проверка не запускается по расписанию (тесты вызывают app.state.background.run_once())."""
    settings = settings or get_settings()
    configure_logging(settings.log_level)
    # Промпт и ключ шифрования читаются сразу: ошибка видна при старте, а не на первом запросе.
    system_prompt = load_system_prompt(settings)
    key = settings.access_encryption_key.get_secret_value() if settings.access_encryption_key else ""
    box = SecretBox(key) if key else None

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        client = http or httpx.AsyncClient()
        app.state.system_prompt = system_prompt
        app.state.session_checker = AutoGraphSessionChecker(settings, client)
        autograph = AutoGraphClient(settings, client)
        telemetry = TelemetryService(settings, autograph)
        checks = AnomalyCheckService(settings, telemetry, AnalyticsClient(settings, client))
        store = build_store(settings)
        access = AccessRepository(store, box)
        scans = ScanService(settings, telemetry, checks, store)
        background = BackgroundScanner(settings, telemetry, autograph, scans, store, access)
        app.state.autograph_client = autograph
        app.state.telemetry_service = telemetry
        app.state.check_service = checks
        app.state.store = store
        app.state.scan_service = scans
        app.state.access = access
        app.state.background = background
        app.state.agent = Agent(
            llm or OpenRouterClient(settings, client),
            tools if tools is not None else build_tools(settings, telemetry, checks, store),
            settings.agent_max_iterations,
        )
        task = None
        if settings.background_configured and background_loop:
            task = asyncio.create_task(background.run_forever(), name="background-scan")
        elif not settings.background_configured:
            log.info("фоновая проверка выключена (FS_BACKGROUND_ENABLED или не задан FS_ACCESS_ENCRYPTION_KEY)")
        try:
            yield
        finally:
            if task is not None:
                task.cancel()
                with suppress(asyncio.CancelledError):
                    await task
            store.dispose()
            if http is None:
                await client.aclose()

    app = FastAPI(title=settings.app_name, version=__version__, lifespan=lifespan)
    app.dependency_overrides[get_settings] = lambda: settings
    app.state.settings = settings
    install_error_handlers(app)
    app.include_router(health.router)
    app.include_router(chat.router)
    app.include_router(telemetry_api.router)
    app.include_router(anomalies_api.router)
    app.include_router(background_api.router)
    return app
