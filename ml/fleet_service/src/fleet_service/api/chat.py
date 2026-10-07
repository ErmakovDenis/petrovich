import asyncio
import logging
import time
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status

from ..agent.loop import Agent
from ..agent.prompt import build_messages
from ..agent.tools import ToolContext
from ..config import Settings, get_settings
from ..schemas.chat import ChatRequest, ChatResponse
from .deps import UserSession, get_agent, get_system_prompt, require_session
from .errors import UNPROCESSABLE

log = logging.getLogger(__name__)

router = APIRouter(prefix="/v1", tags=["chat"])


@router.post("/chat", response_model=ChatResponse)
async def chat(
    request: ChatRequest,
    user: Annotated[UserSession, Depends(require_session)],
    settings: Annotated[Settings, Depends(get_settings)],
    agent: Annotated[Agent, Depends(get_agent)],
    system_prompt: Annotated[str, Depends(get_system_prompt)],
) -> ChatResponse:
    """Ответ ассистента на последнее сообщение пользователя с учётом истории и контекста аномалии."""
    if not settings.llm_configured:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Ассистент на стенде не настроен: не заданы ключ OpenRouter или модель",
        )
    if request.messages[-1].role != "user":
        raise HTTPException(UNPROCESSABLE, "Последнее сообщение должно быть от пользователя")
    limit = settings.chat_max_message_chars
    if len(request.messages[-1].content) > limit:
        raise HTTPException(UNPROCESSABLE, f"Сообщение длиннее {limit} символов")
    # Старые сообщения сверх лимита отбрасываем, длинные из истории обрезаем: отвергнутый вопрос остаётся
    # в истории приложения и не должен ломать следующие запросы. Последнее сообщение (вопрос) остаётся всегда.
    history = [
        t if len(t.content) <= limit else t.model_copy(update={"content": t.content[:limit] + "…"})
        for t in request.messages[-settings.chat_max_messages:]
    ]
    trimmed = request.model_copy(update={"messages": history})

    started = time.monotonic()
    ctx = ToolContext(user.session, user.schema_id, request.utc_offset_minutes)
    try:
        async with asyncio.timeout(settings.chat_timeout_seconds):
            result = await agent.run(build_messages(system_prompt, trimmed), ctx)
    except TimeoutError:
        raise HTTPException(
            status.HTTP_504_GATEWAY_TIMEOUT,
            f"Ассистент не успел ответить за {settings.chat_timeout_seconds:g} с",
        ) from None
    log.info(
        "чат: схема %s, сообщений %d, обращений к модели %d, tools %s, %.1f с",
        user.schema_id, len(trimmed.messages), result.iterations, result.tool_calls or "—",
        time.monotonic() - started,
    )
    return ChatResponse(reply=result.reply)
