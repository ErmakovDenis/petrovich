"""Ошибки — ответ `{"detail": "<понятный текст по-русски>"}` без стека; стек только в логе (замаскированный)."""

import logging

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from ..agent.llm import LLMError, LLMTimeout
from ..agent.loop import AgentIterationLimit

log = logging.getLogger(__name__)

# Числом: имя константы в Starlette менялось (UNPROCESSABLE_ENTITY → UNPROCESSABLE_CONTENT).
UNPROCESSABLE = 422


def _detail(code: int, text: str) -> JSONResponse:
    return JSONResponse({"detail": text}, status_code=code)


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(LLMTimeout)
    async def llm_timeout(_: Request, e: LLMTimeout) -> JSONResponse:
        log.warning("модель: %s", e)
        return _detail(status.HTTP_504_GATEWAY_TIMEOUT, str(e))

    @app.exception_handler(LLMError)
    async def llm_error(_: Request, e: LLMError) -> JSONResponse:
        log.warning("модель: %s", e)
        return _detail(status.HTTP_502_BAD_GATEWAY, f"Модель не ответила: {e}")

    @app.exception_handler(AgentIterationLimit)
    async def iteration_limit(_: Request, e: AgentIterationLimit) -> JSONResponse:
        log.warning("агент: превышен лимит обращений к модели (%d)", e.limit)
        return _detail(status.HTTP_502_BAD_GATEWAY, str(e))

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_: Request, e: RequestValidationError) -> JSONResponse:
        problems = "; ".join(
            f"{'.'.join(str(p) for p in err.get('loc', ()) if p != 'body')}: {err.get('msg')}" for err in e.errors()
        )
        return _detail(UNPROCESSABLE, f"Неверный запрос: {problems}")

    @app.exception_handler(Exception)
    async def unexpected(_: Request, e: Exception) -> JSONResponse:
        log.exception("необработанная ошибка")
        return _detail(status.HTTP_500_INTERNAL_SERVER_ERROR, "Внутренняя ошибка стенда")
