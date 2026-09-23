"""Точка входа: uvicorn predictive_antifraud.main:app"""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import __version__
from .api import health, v1
from .config import Settings, get_settings
from .models.registry import ModelRegistry
from .services.antifraud import AntifraudService
from .services.predictive import PredictiveService


def create_app(settings: Settings | None = None, registry: ModelRegistry | None = None) -> FastAPI:
    settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        reg = registry
        if reg is None:
            reg = ModelRegistry(settings.models_dir)
            reg.load_all()
        app.state.registry = reg
        app.state.predictive = PredictiveService(reg, settings)
        app.state.antifraud = AntifraudService(reg, settings)
        yield

    app = FastAPI(title=settings.app_name, version=__version__, lifespan=lifespan)
    app.dependency_overrides[get_settings] = lambda: settings
    app.include_router(health.router)
    app.include_router(v1.router)
    return app


logging.basicConfig(level=logging.INFO)
app = create_app()
