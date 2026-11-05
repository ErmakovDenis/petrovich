"""Ошибки — ответ `{"detail": "<понятный текст по-русски>"}` без стека; стек только в логе (замаскированный)."""

import logging
import math

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from ..agent.llm import LLMBreakerOpen, LLMCreditsExhausted, LLMError, LLMRetryable, LLMTimeout
from ..agent.loop import AgentIterationLimit
from ..autograph.errors import AutoGraphUnavailable, SessionInvalid, TripTablesTooLarge
from ..limits import Overloaded, RateLimited

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

    @app.exception_handler(LLMCreditsExhausted)
    async def llm_credits(request: Request, e: LLMCreditsExhausted) -> JSONResponse:
        request.app.state.metrics.rejections.labels("llm_credits").inc()
        # В лог (ERROR) уже записал клиент модели; пользователю — без подробностей про кредиты.
        return _detail(status.HTTP_503_SERVICE_UNAVAILABLE, "Ассистент временно недоступен, попробуйте позже")

    @app.exception_handler(LLMRetryable)
    async def llm_retryable(request: Request, e: LLMRetryable) -> JSONResponse:
        request.app.state.metrics.rejections.labels(
            "breaker_llm" if isinstance(e, LLMBreakerOpen) else "llm_unavailable").inc()
        # Подробности (текст провайдера или прокси) — только в лог: пользователю они ничего не скажут.
        log.warning("модель: %s", e)
        response = _detail(status.HTTP_503_SERVICE_UNAVAILABLE, "Модель временно недоступна, повторите позже")
        if e.retry_after is not None:
            response.headers["Retry-After"] = str(max(1, round(e.retry_after)))
        return response

    @app.exception_handler(LLMError)
    async def llm_error(_: Request, e: LLMError) -> JSONResponse:
        log.warning("модель: %s", e)
        return _detail(status.HTTP_502_BAD_GATEWAY, f"Модель не ответила: {e}")

    @app.exception_handler(RateLimited)
    async def rate_limited(request: Request, e: RateLimited) -> JSONResponse:
        log.info("лимит чата пользователя: повтор через %.0f с", e.retry_after)
        request.app.state.metrics.rejections.labels("chat_rate_limit").inc()
        response = _detail(status.HTTP_429_TOO_MANY_REQUESTS, str(e))
        response.headers["Retry-After"] = str(math.ceil(e.retry_after))
        return response

    @app.exception_handler(Overloaded)
    async def overloaded(request: Request, e: Overloaded) -> JSONResponse:
        log.warning("перегрузка: %s — отказ", e)
        request.app.state.metrics.rejections.labels("chat_queue_full").inc()
        response = _detail(status.HTTP_503_SERVICE_UNAVAILABLE, "Высокая нагрузка на ассистента, повторите через минуту")
        response.headers["Retry-After"] = "60"
        return response

    @app.exception_handler(AgentIterationLimit)
    async def iteration_limit(_: Request, e: AgentIterationLimit) -> JSONResponse:
        log.warning("агент: превышен лимит обращений к модели (%d)", e.limit)
        return _detail(status.HTTP_502_BAD_GATEWAY, str(e))

    @app.exception_handler(SessionInvalid)
    async def session_invalid(_: Request, e: SessionInvalid) -> JSONResponse:
        # Токен истёк между проверкой сессии и запросом данных: приложение войдёт заново и повторит запрос.
        return JSONResponse(
            {"detail": str(e)},
            status_code=status.HTTP_401_UNAUTHORIZED,
            headers={"WWW-Authenticate": "Bearer"},
        )

    @app.exception_handler(AutoGraphUnavailable)
    async def autograph_unavailable(request: Request, e: AutoGraphUnavailable) -> JSONResponse:
        request.app.state.metrics.rejections.labels(e.reason).inc()
        log.warning("AutoGRAPH: %s", e)
        return _detail(status.HTTP_503_SERVICE_UNAVAILABLE, str(e))

    @app.exception_handler(TripTablesTooLarge)
    async def too_large(_: Request, e: TripTablesTooLarge) -> JSONResponse:
        log.warning("AutoGRAPH: %s", e)
        return _detail(status.HTTP_502_BAD_GATEWAY, f"AutoGRAPH вернул сбойный ответ: {e}")

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
