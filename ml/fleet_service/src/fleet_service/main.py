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
from .agent.mail_tools import build_mail_tools
from .agent.prompt import load_system_prompt
from .agent.store_tools import build_store_tools
from .agent.tools import ToolRegistry
from .analytics.client import AnalyticsClient
from .api import anomalies as anomalies_api
from .api import background as background_api
from .api import chat, emails as emails_api, health
from .api import status as status_api
from .api import telegram as telegram_api
from .api import telemetry as telemetry_api
from .api.errors import install_error_handlers
from .autograph.client import AutoGraphClient
from .autograph.session import AutoGraphSessionChecker
from .background.access import AccessRepository
from .background.crypto import SecretBox
from .background.scheduler import BackgroundScanner
from .breaker import CircuitBreaker
from .config import Settings, get_settings
from .limits import AdmissionLimiter, Limits, TokenBuckets, UpstreamLimiter
from .log_masking import configure_logging
from .mail.recipients import Recipients
from .mail.sender import SmtpSender
from .mail.service import EmailService
from .rules.check import AnomalyCheckService
from .store.db import create_db_engine
from .store.migrate import upgrade
from .store.repository import AnomalyRepository
from .store.scan import ScanService
from .telegram.links import TelegramLinks
from .telegram.notify import TelegramNotifier
from .telegram.sessions import TelegramSessions
from .telemetry.service import TelemetryService

log = logging.getLogger(__name__)


def build_tools(
    settings: Settings, telemetry: TelemetryService, checks: AnomalyCheckService, store: AnomalyRepository,
    emails: EmailService | None = None,
) -> ToolRegistry:
    """Tools ассистента: телеметрия (шаг 2), проверка машины (шаг 3), хранилище аномалий и решений (шаг 4),
    черновики писем (шаг 6; только если письма настроены и разрешены в запросе)."""
    registry = ToolRegistry(max_result_chars=settings.tool_max_result_chars)
    tools = [*build_fleet_tools(settings, telemetry, checks), *build_store_tools(settings, telemetry, store)]
    if emails is not None:
        tools += build_mail_tools(settings, emails)
    for tool in tools:
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
    # Файл получателей писем читается при старте: ошибка в нём видна сразу.
    recipients = Recipients.load(settings.email_recipients_path) if settings.email_configured else None

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        client = http or httpx.AsyncClient()
        app.state.system_prompt = system_prompt
        # Пределы нагрузки на процесс (limits.py): общие для чата, проверок, scan и фоновой проверки.
        autograph_limit = UpstreamLimiter("autograph", settings.autograph_concurrency)
        predictive_limit = UpstreamLimiter("predictive", settings.predictive_concurrency)
        app.state.limits = Limits(
            chat_rate=TokenBuckets(settings.chat_rate_per_minute, settings.chat_burst),
            chat=AdmissionLimiter("chat", settings.chat_concurrency, settings.chat_queue_max),
            autograph=autograph_limit,
            predictive=predictive_limit,
        )
        # Circuit breaker'ы на процесс (breaker.py): состояние — в /health и /v1/status.
        breakers = {name: CircuitBreaker(name, settings.breaker_failures, settings.breaker_window_seconds,
                                         settings.breaker_open_seconds)
                    for name in ("llm", "autograph", "predictive")}
        app.state.breakers = breakers
        app.state.session_checker = AutoGraphSessionChecker(settings, client, autograph_limit, breakers["autograph"])
        autograph = AutoGraphClient(settings, client, autograph_limit, breakers["autograph"])
        telemetry = TelemetryService(settings, autograph)
        analytics = AnalyticsClient(settings, client, predictive_limit, breakers["predictive"])
        checks = AnomalyCheckService(settings, telemetry, analytics)
        store = build_store(settings)
        access = AccessRepository(store, box)
        scans = ScanService(settings, telemetry, checks, store)
        background = BackgroundScanner(settings, telemetry, autograph, scans, store, access)
        emails = EmailService(settings, recipients, SmtpSender(settings), store) if recipients is not None else None
        app.state.emails = emails
        app.state.autograph_client = autograph
        app.state.telemetry_service = telemetry
        app.state.check_service = checks
        app.state.store = store
        app.state.scan_service = scans
        app.state.access = access
        app.state.background = background
        # Telegram-бот (ml/telegram_bot): подключения чатов и уведомления по доступу фоновой проверки пользователя.
        telegram_links = TelegramLinks(store)
        telegram_sessions = TelegramSessions(access, app.state.session_checker, autograph)
        app.state.telegram_links = telegram_links
        app.state.telegram_sessions = telegram_sessions
        app.state.telegram_notifier = TelegramNotifier(settings, telegram_links, telegram_sessions, telemetry, store)
        app.state.agent = Agent(
            llm or OpenRouterClient(settings, client, breakers["llm"]),
            tools if tools is not None else build_tools(settings, telemetry, checks, store, emails),
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
    app.include_router(emails_api.router)
    app.include_router(status_api.router)
    app.include_router(telegram_api.router)
    return app
