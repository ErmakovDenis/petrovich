"""Отправка письма по SMTP (FS_SMTP_*). smtplib — в отдельном потоке, чтобы не держать цикл событий.

Тексты ошибок безопасно показывать пользователю и писать в журнал: в них нет адресов и текста письма (исключения
smtplib, например SMTPRecipientsRefused, содержат адреса — наружу они не выходят).
"""

import asyncio
import email.policy
import logging
import smtplib
import ssl
from email.message import EmailMessage
from email.utils import formatdate, make_msgid

from ..config import Settings

log = logging.getLogger(__name__)


class SendFailed(Exception):
    """Почтовый сервер не принял письмо — оно не отправлено. [partial] — письмо могло уйти (части получателей или
    связь оборвалась после передачи текста): повторять нельзя (будет дубль), черновик закрывается."""

    def __init__(self, message: str, partial: bool = False):
        super().__init__(message)
        self.partial = partial


class SmtpSender:
    def __init__(self, settings: Settings):
        self._s = settings

    def build(self, to: list[str], subject: str, body: str) -> EmailMessage:
        msg = EmailMessage()
        msg["From"] = self._s.smtp_from
        msg["To"] = ", ".join(to)
        msg["Subject"] = subject
        msg["Date"] = formatdate(localtime=False)
        msg["Message-ID"] = make_msgid(domain=self._s.smtp_from.rpartition("@")[2] or None)
        msg.set_content(body)
        return msg

    async def send(self, to: list[str], subject: str, body: str) -> None:
        msg = self.build(to, subject, body)
        stage = {"data": False, "done": False}
        try:
            await asyncio.to_thread(self._send, msg, to, stage)
        except SendFailed:
            raise
        except (smtplib.SMTPException, OSError) as e:
            failure = self._failure(e, stage)
            if failure is not None:
                raise failure from None

    @staticmethod
    def _failure(e: Exception, stage: dict) -> SendFailed | None:
        """Ошибка smtplib → текст без адресов. Письмо принято (250 на DATA), сбой — при прощании: не ошибка."""
        if stage["done"]:
            return None
        if isinstance(e, smtplib.SMTPAuthenticationError):
            log.warning("SMTP: сервер не принял логин стенда (%s)", e.smtp_code)
            return SendFailed("почтовый сервер не принял логин стенда")
        if isinstance(e, smtplib.SMTPResponseException):
            # Сервер ответил отказом (в том числе на DATA) — письмо не принято.
            log.warning("SMTP: сервер ответил ошибкой %s", e.smtp_code)
            return SendFailed(f"почтовый сервер ответил ошибкой {e.smtp_code}")
        if stage["data"]:
            # Текст письма уже передавался: сервер мог его принять, а ответ не дошёл.
            log.warning("SMTP: связь оборвалась при передаче письма: %s", type(e).__name__)
            return SendFailed("связь с почтовым сервером оборвалась при передаче письма — оно могло уйти, "
                              "проверьте у получателя", partial=True)
        log.warning("SMTP: сервер недоступен: %s", type(e).__name__)
        return SendFailed("почтовый сервер недоступен")

    def _send(self, msg: EmailMessage, to: list[str], stage: dict) -> None:
        """MAIL, RCPT по одному, DATA — по шагам: известно, дошло ли дело до передачи текста ([stage])."""
        s = self._s
        context = ssl.create_default_context()
        if s.smtp_security == "ssl":
            client: smtplib.SMTP = smtplib.SMTP_SSL(s.smtp_host, s.smtp_port, timeout=s.smtp_timeout_seconds,
                                                    context=context)
        else:
            client = smtplib.SMTP(s.smtp_host, s.smtp_port, timeout=s.smtp_timeout_seconds)
        with client:
            client.ehlo()
            if s.smtp_security == "starttls":
                client.starttls(context=context)
                client.ehlo()
            if s.smtp_username:
                password = s.smtp_password.get_secret_value() if s.smtp_password else ""
                client.login(s.smtp_username, password)
            code, _ = client.mail(s.smtp_from)
            if code != 250:
                raise smtplib.SMTPSenderRefused(code, b"", s.smtp_from)
            refused = [r for r in to if client.rcpt(r)[0] not in (250, 251)]
            if len(refused) == len(to):
                log.warning("SMTP: сервер отказался принять получателя")
                raise SendFailed("почтовый сервер отказался принять получателя")
            stage["data"] = True
            # Строки — через CRLF, как требует SMTP (send_message делает так же).
            code, _ = client.data(msg.as_bytes(policy=email.policy.SMTP))
            if code != 250:
                raise smtplib.SMTPDataError(code, b"")
            stage["done"] = True
            if refused:
                # Письмо ушло не всем: повтор дал бы дубли остальным.
                raise SendFailed(f"почтовый сервер не принял часть получателей ({len(refused)}) — письмо ушло не всем",
                                 partial=True)
