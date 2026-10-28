"""Клиент стенда fleet_service для бота: ключ бота и id чата в заголовках, стенд сам подставляет сессию AutoGRAPH
пользователя чата (`ml/fleet_service`, api/telegram.py). Ответы — JSON стенда как есть (camelCase)."""

import logging
from typing import Any
from urllib.parse import quote

import httpx

log = logging.getLogger(__name__)

UNAVAILABLE = "Стенд «Петровича» сейчас не отвечает — попробуйте чуть позже."


class StandError(Exception):
    """Отказ стенда: [status] — HTTP-код (None — стенд не ответил), текст — для пользователя."""

    def __init__(self, status: int | None, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


class Stand:
    def __init__(self, base_url: str, token: str, timeout: float, http: httpx.AsyncClient | None = None):
        """[http] подменяется в тестах (транспорт к приложению стенда)."""
        self._http = http or httpx.AsyncClient()
        self._base = base_url.rstrip("/")
        self._token = token
        self._timeout = timeout

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _call(self, method: str, path: str, chat_id: str | int | None = None, **kwargs: Any) -> Any:
        headers = {"Authorization": f"Bearer {self._token}"}
        if chat_id is not None:
            headers["X-Telegram-Chat-Id"] = str(chat_id)
        try:
            resp = await self._http.request(method, self._base + path, headers=headers, timeout=self._timeout, **kwargs)
        except httpx.HTTPError as e:
            log.warning("стенд: %s %s не ответил: %s", method, path.split("?")[0], type(e).__name__)
            raise StandError(None, UNAVAILABLE) from e
        if resp.status_code >= 400:
            detail = _detail(resp)
            if resp.status_code >= 500 and not detail:
                detail = UNAVAILABLE
            raise StandError(resp.status_code, detail or f"Стенд ответил {resp.status_code}")
        return resp.json() if resp.content else None

    # --- подключение и подписка ---

    async def connect(self, chat_id: int, code: str, title: str | None = None) -> dict:
        return await self._call("POST", "/v1/telegram/bot/connect",
                                json={"chatId": str(chat_id), "code": code, "title": title})

    async def pair_code(self, chat_id: int, title: str | None = None) -> dict:
        return await self._call("POST", "/v1/telegram/bot/pair-code", json={"chatId": str(chat_id), "title": title})

    async def me(self, chat_id: int) -> dict:
        return await self._call("GET", "/v1/telegram/me", chat_id)

    async def set_subscription(self, chat_id: int, subscription: dict) -> dict:
        return await self._call("PUT", "/v1/telegram/me/subscription", chat_id, json=subscription)

    async def unlink(self, chat_id: int) -> None:
        await self._call("DELETE", "/v1/telegram/me", chat_id)

    async def outbox(self) -> dict:
        return await self._call("POST", "/v1/telegram/bot/outbox")

    # --- аномалии и решения ---

    async def problems(self, chat_id: int, limit: int | None = None) -> dict:
        """Ждущие решения по настройкам списков чата; без [limit] — сколько строк выбрал пользователь."""
        return await self._call("GET", "/v1/telegram/me/problems", chat_id,
                                params={"limit": limit} if limit is not None else None)

    async def report(self, chat_id: int, kind: str = "daily") -> dict:
        """Доклад за сутки (daily) или сводка за прошлую неделю (weekly) сейчас."""
        return await self._call("GET", "/v1/telegram/me/report", chat_id, params={"kind": kind})

    async def anomaly(self, chat_id: int, anomaly_id: str, offset: int) -> dict:
        return await self._call("GET", f"/v1/anomalies/{_segment(anomaly_id)}", chat_id,
                                params={"utcOffsetMinutes": offset})

    async def resolve(self, chat_id: int, anomaly_id: str, resolution: str | None, reason: str | None,
                      offset: int) -> dict:
        return await self._call("POST", f"/v1/anomalies/{_segment(anomaly_id)}/resolve", chat_id,
                                json={"resolution": resolution, "reason": reason, "utcOffsetMinutes": offset})

    # --- ассистент и письма ---

    async def chat(self, chat_id: int, messages: list[dict], offset: int, anomaly_id: str | None = None) -> dict:
        body: dict[str, Any] = {"messages": messages, "utcOffsetMinutes": offset, "allowEmail": True}
        if anomaly_id:
            body["anomalyId"] = anomaly_id
        return await self._call("POST", "/v1/chat", chat_id, json=body)

    async def send_email(self, chat_id: int, draft_id: str) -> dict:
        return await self._call("POST", f"/v1/emails/{_segment(draft_id)}/confirm", chat_id, json={})

    async def cancel_email(self, chat_id: int, draft_id: str) -> dict:
        return await self._call("POST", f"/v1/emails/{_segment(draft_id)}/cancel", chat_id, json={})


def _segment(value: str) -> str:
    return quote(value, safe="")


def _detail(resp: httpx.Response) -> str:
    try:
        detail = resp.json().get("detail")
    except (ValueError, AttributeError):
        return ""
    return detail if isinstance(detail, str) else ""
