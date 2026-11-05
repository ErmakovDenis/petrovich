"""Приложение шлюза: POST /v1/chat/completions, GET /health и GET /metrics (при FS_METRICS_ENABLED).

Коды ответа рассчитаны на повторы прокси LiteLLM: 402, 429 и 5xx передаются с error.code, 422 GigaChat — это ошибка
запроса, поэтому 400: повторять её бесполезно. Запросов к GigaChat одновременно не больше FS_GIGACHAT_CONCURRENCY;
не дождался места за срок запроса — 429 с Retry-After.
"""

import asyncio
import logging
import ssl
import time
from collections.abc import Awaitable, Callable
from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from ..config import Settings
from ..log_masking import configure_logging
from ..metrics import Metrics
from .convert import InvalidRequest, from_gigachat, to_gigachat
from .errors import USER_AGENT, GatewayError, gigachat_message
from .token import TokenSource

log = logging.getLogger(__name__)

NO_SLOT_RETRY_AFTER = "5"
# Меньше этого остатка срока после ожидания места — обращение к GigaChat не начинается, с.
MIN_CALL_SECONDS = 0.05
# Как часто проверять, что вызывающая сторона ещё ждёт ответа, с.
DISCONNECT_POLL_SECONDS = 0.5


def tls_context(settings: Settings) -> ssl.SSLContext:
    """Проверка сертификата включена всегда: настройки отключения нет. Неверный файл или ключ без файла — ошибка при
    старте, а не на первом запросе. Без ключа шлюз к GigaChat не обращается, и файл не обязателен."""
    bundle = settings.gigachat_ca_bundle
    if bundle is None:
        if settings.gigachat_credentials and settings.gigachat_credentials.get_secret_value():
            raise ValueError("Задан FS_GIGACHAT_CREDENTIALS, но не задан FS_GIGACHAT_CA_BUNDLE: нужен сертификат Минцифры")
        return ssl.create_default_context()
    try:
        context = ssl.create_default_context(cafile=str(bundle))
    except (OSError, ssl.SSLError) as e:
        raise ValueError(f"FS_GIGACHAT_CA_BUNDLE: не удалось прочитать сертификаты из {bundle}: {e}") from e
    if not context.get_ca_certs():
        raise ValueError(f"FS_GIGACHAT_CA_BUNDLE: в {bundle} нет корневого сертификата")
    return context


class Gateway:
    def __init__(self, settings: Settings, http: httpx.AsyncClient, metrics: Metrics, clock: Callable[[], float]):
        self._settings = settings
        self._http = http
        self._metrics = metrics
        self._tokens = TokenSource(settings, http, metrics, clock)
        self.slots = asyncio.Semaphore(settings.gigachat_concurrency)

    @property
    def configured(self) -> bool:
        key = self._settings.gigachat_credentials
        return bool(key and key.get_secret_value())

    async def complete(self, body: dict[str, Any]) -> dict[str, Any]:
        s = self._settings
        if not self.configured:
            raise GatewayError(503, "Шлюз GigaChat не настроен: не задан FS_GIGACHAT_CREDENTIALS")
        request = self._convert(body)
        timeout = s.gigachat_timeout_seconds
        started = time.monotonic()
        try:
            async with asyncio.timeout(timeout):
                await self.slots.acquire()
        except TimeoutError:
            raise self._no_slot() from None
        left = timeout - (time.monotonic() - started)
        if left < MIN_CALL_SECONDS:
            # Место досталось в последний миг срока: обращение всё равно не успеет, а токены модели потратит.
            self.slots.release()
            raise self._no_slot()
        try:
            async with asyncio.timeout(left):
                data = await self._call(request)
        except TimeoutError:
            log.warning("GigaChat не ответил за %g с", timeout)
            raise GatewayError(504, f"GigaChat не ответил за {timeout:g} с") from None
        finally:
            self.slots.release()
        return self._response(data, request["model"])

    def _no_slot(self) -> GatewayError:
        s = self._settings
        self._metrics.rejections.labels("gigachat_no_slot").inc()
        log.warning("шлюз: нет места за %g с (FS_GIGACHAT_CONCURRENCY=%d) — 429", s.gigachat_timeout_seconds,
                    s.gigachat_concurrency)
        return GatewayError(429, "Заняты все потоки GigaChat", NO_SLOT_RETRY_AFTER)

    def _convert(self, body: dict[str, Any]) -> dict[str, Any]:
        s = self._settings
        try:
            return to_gigachat(body, s.gigachat_model, s.gigachat_arguments_format)
        except InvalidRequest as e:
            raise GatewayError(400, str(e)) from None
        except (AttributeError, TypeError):
            # Сообщения или tools неверной формы: это ошибка запроса, повторять её бесполезно.
            raise GatewayError(400, "Неверный формат сообщений или tools") from None

    async def _call(self, request: dict[str, Any]) -> dict[str, Any]:
        token = await self._tokens.get()
        resp = await self._post(request, token)
        if resp.status_code == 401:
            # Токен устарел раньше срока: загружаем новый и повторяем один раз.
            log.info("GigaChat: токен отвергнут (401), загружаем новый")
            resp = await self._post(request, await self._tokens.get(rejected=token))
        if resp.status_code != 200:
            raise self._status_error(resp)
        try:
            data = resp.json()
        except ValueError:
            data = None
        if not isinstance(data, dict):
            raise GatewayError(502, "GigaChat вернул ответ не в формате JSON")
        return data

    async def _post(self, request: dict[str, Any], token: str) -> httpx.Response:
        s = self._settings
        url = s.gigachat_base_url.rstrip("/") + "/chat/completions"
        headers = {"Authorization": f"Bearer {token}", "Accept": "application/json", "User-Agent": USER_AGENT}
        try:
            with self._metrics.upstream("gigachat") as call:
                try:
                    resp = await self._http.post(url, json=request, headers=headers, timeout=s.gigachat_timeout_seconds)
                except (httpx.TimeoutException, asyncio.CancelledError):
                    # Отмена — это срок запроса в шлюзе или ушедший клиент: GigaChat за это время не ответил.
                    call.outcome = "timeout"
                    raise
                call.status(resp.status_code)
        except httpx.TimeoutException:
            log.warning("GigaChat не ответил за %g с", s.gigachat_timeout_seconds)
            raise GatewayError(504, f"GigaChat не ответил за {s.gigachat_timeout_seconds:g} с") from None
        except httpx.HTTPError as e:
            log.warning("GigaChat недоступен: %s: %s", type(e).__name__, e)
            raise GatewayError(503, f"GigaChat недоступен: {type(e).__name__}") from e
        return resp

    def _status_error(self, resp: httpx.Response) -> GatewayError:
        code = resp.status_code
        text = f"GigaChat ответил {code}: {gigachat_message(resp)}"
        if code == 402:
            # Считает шлюз: если ответит запасная модель или резерв, до стенда 402 не дойдёт, и иначе сигнала не будет.
            self._metrics.llm_credits_exhausted.labels("gigachat").inc()
            log.error("GigaChat: кончились токены модели (402) — нужен владелец стенда: %s", text)
        elif code == 422:
            log.warning("GigaChat отверг запрос (422): %s", text)
            return GatewayError(400, text)
        else:
            log.warning("%s", text)
        return GatewayError(code, text, resp.headers.get("retry-after"))

    def _response(self, data: dict[str, Any], model: str) -> dict[str, Any]:
        choices = data.get("choices")
        choice = choices[0] if isinstance(choices, list) and choices and isinstance(choices[0], dict) else None
        if choice is None or not isinstance(choice.get("message"), dict):
            log.warning("GigaChat вернул ответ без вариантов или сообщения")
            raise GatewayError(502, "GigaChat вернул ответ без вариантов или сообщения")
        finish = choice.get("finish_reason")
        if finish == "error":
            # Невалидные аргументы функции: генерация случайна, повтор прокси может дать верный ответ.
            log.warning("GigaChat: модель вернула невалидные аргументы функции (finish_reason=error)")
            raise GatewayError(502, "GigaChat: модель вернула невалидные аргументы функции")
        if finish == "blacklist":
            self._metrics.gigachat_blacklist.inc()
            log.warning("GigaChat: тематическое ограничение (finish_reason=blacklist), модель %s", model)
        return from_gigachat(data, model)


async def _until_disconnected(request: Request) -> None:
    while not await request.is_disconnected():
        await asyncio.sleep(DISCONNECT_POLL_SECONDS)


async def _unless_abandoned(work: Awaitable[dict[str, Any]], request: Request) -> dict[str, Any] | None:
    """Результат [work] или None, если вызывающая сторона ушла. Прокси и стенд обрывают запрос по своему сроку: при одном
    потоке брошенный запрос иначе держал бы его до срока шлюза, а из очереди ещё и тратил бы токены."""
    task = asyncio.ensure_future(work)
    watcher = asyncio.ensure_future(_until_disconnected(request))
    try:
        await asyncio.wait({task, watcher}, return_when=asyncio.FIRST_COMPLETED)
        if task.done():
            return task.result()
        log.info("шлюз: вызывающая сторона не дождалась ответа, запрос отменён")
        return None
    finally:
        watcher.cancel()
        task.cancel()


def create_gateway(settings: Settings, http: httpx.AsyncClient | None = None,
                   clock: Callable[[], float] = time.time) -> FastAPI:
    """[http] и [clock] подменяются в тестах. Свой клиент создаётся сразу: сертификат проверяется при старте."""
    configure_logging(settings.log_level)
    client = http or httpx.AsyncClient(verify=tls_context(settings))
    metrics = Metrics()
    gateway = Gateway(settings, client, metrics, clock)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        if http is None:
            await client.aclose()

    app = FastAPI(title="Шлюз GigaChat", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.metrics = metrics
    app.state.gateway = gateway

    @app.get("/health")
    def health() -> dict[str, object]:
        """Живой без ключа: сервис запускается всегда, а чат в этом случае отвечает 503."""
        return {"status": "ok", "configured": gateway.configured}

    @app.post("/v1/chat/completions")
    async def completions(request: Request) -> JSONResponse:
        try:
            body = await request.json()
        except ValueError:
            body = None
        try:
            if not isinstance(body, dict):
                raise GatewayError(400, "Тело запроса должно быть объектом JSON")
            result = await _unless_abandoned(gateway.complete(body), request)
            # Читать ответ уже некому; 499 — обрыв со стороны клиента.
            return JSONResponse(result) if result is not None else Response(status_code=499)
        except GatewayError as e:
            return e.response()

    if settings.metrics_enabled:
        @app.get("/metrics", include_in_schema=False)
        def prometheus() -> Response:
            return Response(generate_latest(metrics.registry), media_type=CONTENT_TYPE_LATEST)

    return app
