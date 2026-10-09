"""Точка входа: uvicorn --factory fleet_service.main:create_app

Приложение создаётся фабрикой, а не при импорте: импорт модуля в тестах не читает .env и переменные окружения.
"""

from contextlib import asynccontextmanager
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
from .api import chat, health
from .api import telemetry as telemetry_api
from .api.errors import install_error_handlers
from .autograph.client import AutoGraphClient
from .autograph.session import AutoGraphSessionChecker
from .config import Settings, get_settings
from .log_masking import configure_logging
from .rules.check import AnomalyCheckService
from .store.db import create_db_engine
from .store.migrate import upgrade
from .store.repository import AnomalyRepository
from .store.scan import ScanService
from .telemetry.service import TelemetryService


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
) -> FastAPI:
    """[http], [llm], [tools] подменяются в тестах подставными серверами и моделью."""
    settings = settings or get_settings()
    configure_logging(settings.log_level)
    # Промпт читается сразу: ошибка в пути видна при старте, а не на первом вопросе.
    system_prompt = load_system_prompt(settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        client = http or httpx.AsyncClient()
        app.state.system_prompt = system_prompt
        app.state.session_checker = AutoGraphSessionChecker(settings, client)
        telemetry = TelemetryService(settings, AutoGraphClient(settings, client))
        checks = AnomalyCheckService(settings, telemetry, AnalyticsClient(settings, client))
        store = build_store(settings)
        app.state.telemetry_service = telemetry
        app.state.check_service = checks
        app.state.store = store
        app.state.scan_service = ScanService(settings, telemetry, checks, store)
        app.state.agent = Agent(
            llm or OpenRouterClient(settings, client),
            tools if tools is not None else build_tools(settings, telemetry, checks, store),
            settings.agent_max_iterations,
        )
        try:
            yield
        finally:
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
    return app
