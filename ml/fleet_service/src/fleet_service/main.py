"""Точка входа: uvicorn --factory fleet_service.main:create_app

Приложение создаётся фабрикой, а не при импорте: импорт модуля в тестах не читает .env и переменные окружения.
"""

from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI

from . import __version__
from .agent.fleet_tools import build_fleet_tools
from .agent.llm import LLMClient, OpenRouterClient
from .agent.loop import Agent
from .agent.prompt import load_system_prompt
from .agent.tools import ToolRegistry
from .api import chat, health
from .api import telemetry as telemetry_api
from .api.errors import install_error_handlers
from .autograph.client import AutoGraphClient
from .autograph.session import AutoGraphSessionChecker
from .config import Settings, get_settings
from .log_masking import configure_logging
from .telemetry.service import TelemetryService


def build_tools(settings: Settings, telemetry: TelemetryService) -> ToolRegistry:
    """Tools ассистента: телеметрия (шаг 2); проверки и аномалии подключаются следующими шагами."""
    registry = ToolRegistry(max_result_chars=settings.tool_max_result_chars)
    for tool in build_fleet_tools(settings, telemetry):
        registry.register(tool)
    return registry


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
        app.state.telemetry_service = telemetry
        app.state.agent = Agent(
            llm or OpenRouterClient(settings, client),
            tools if tools is not None else build_tools(settings, telemetry),
            settings.agent_max_iterations,
        )
        try:
            yield
        finally:
            if http is None:
                await client.aclose()

    app = FastAPI(title=settings.app_name, version=__version__, lifespan=lifespan)
    app.dependency_overrides[get_settings] = lambda: settings
    app.state.settings = settings
    install_error_handlers(app)
    app.include_router(health.router)
    app.include_router(chat.router)
    app.include_router(telemetry_api.router)
    return app
