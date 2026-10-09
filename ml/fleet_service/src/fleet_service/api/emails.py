from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status

from ..mail.service import Draft, EmailRefused, EmailService
from ..schemas.emails import EmailResult
from .deps import UserSession, require_session, user_name

router = APIRouter(prefix="/v1/emails", tags=["emails"])

User = Annotated[UserSession, Depends(require_session)]
UserName = Annotated[str | None, Depends(user_name)]


def get_emails(request: Request) -> EmailService:
    emails = request.app.state.emails
    if emails is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Письма на стенде не настроены")
    return emails


Emails = Annotated[EmailService, Depends(get_emails)]


def _result(draft: Draft) -> EmailResult:
    return EmailResult(id=draft.id, status=draft.status, sent_at=draft.sent_at)


@router.post("/{draft_id}/confirm", response_model=EmailResult, response_model_by_alias=True)
async def confirm(draft_id: str, user: User, name: UserName, emails: Emails) -> EmailResult:
    """Отправить черновик — только по нажатию «Отправить» в приложении. Черновик подтверждается один раз, пока не
    истёк срок, и только тем пользователем (схема и логин), для которого его подготовил ассистент."""
    try:
        return _result(await emails.confirm(draft_id, user.schema_id, name))
    except EmailRefused as e:
        raise HTTPException(e.status, str(e)) from None


@router.post("/{draft_id}/cancel", response_model=EmailResult, response_model_by_alias=True)
async def cancel(draft_id: str, user: User, name: UserName, emails: Emails) -> EmailResult:
    """Отменить черновик: подтвердить его потом нельзя."""
    try:
        return _result(await emails.cancel(draft_id, user.schema_id, name))
    except EmailRefused as e:
        raise HTTPException(e.status, str(e)) from None
