"""Явные команды alias из последнего сообщения; данные tools и история не дают согласия."""

import re
from dataclasses import dataclass
from typing import TYPE_CHECKING

from ..identity.principal import IdentityRejected
from ..telemetry.service import VehicleNotFound
from .repository import MemoryRepository, normalize_alias
from .resolver import VehicleAmbiguous, VehicleResolver, official_matches

if TYPE_CHECKING:
    from ..agent.tools import ToolContext

ALIASES = "memory_aliases"


@dataclass(frozen=True)
class AliasIntent:
    action: str
    alias: str
    vehicle_reference: str | None = None


def alias_intent(message: str) -> AliasIntent | None:
    # Консервативная грамматика MVP. Вопросы, цитаты и вложенные команды не являются согласием.
    save = re.fullmatch(r"Запомни:\s*([^\r\n]+?)\s+[—–-]\s+([^\r\n]+)", message.strip(), re.IGNORECASE)
    if save:
        return AliasIntent("set", save[1].strip(), save[2].strip())
    forget = re.fullmatch(r"Забудь (?:название|прозвище)\s+([^\r\n]+)", message.strip(), re.IGNORECASE)
    if forget:
        return AliasIntent("delete", forget[1].strip())
    return None


class AliasService:
    def __init__(self, memory: MemoryRepository, resolver: VehicleResolver):
        self.memory = memory
        self.resolver = resolver

    async def mutate(
        self,
        ctx: "ToolContext",
        action: str,
        alias: str,
        operation_id: str,
        vehicle_reference: str | None = None,
    ) -> dict:
        if ALIASES not in ctx.features:
            raise IdentityRejected()
        principal = await self.resolver.principal(ctx)
        intent = ctx.alias_intent
        if (
            intent is None
            or intent.action != action
            or normalize_alias(intent.alias) != normalize_alias(alias)
            or (action == "set" and intent.vehicle_reference != vehicle_reference)
        ):
            return {
                "status": "confirmation_required",
                "message": "Напишите отдельной командой: «Запомни: прозвище — точное имя, номер или ID машины» "
                "либо «Забудь прозвище название». Без явного запроса память не изменена.",
            }
        vehicle_id = None
        if action == "set":
            vehicle_id = await self.resolver.resolve(ctx, vehicle_reference)
            # Повторная проверка непосредственно перед транзакцией; старый кэш не даёт права записи.
            visible = await self.resolver.vehicles(ctx)
            target = next((v for v in visible if v.id == vehicle_id), None)
            if target is None:
                raise VehicleNotFound()
            collisions = {
                v.id: v
                for ref in {alias, normalize_alias(alias)}
                for v in official_matches(visible, ref)
                if v.id != vehicle_id
            }
            if collisions:
                raise VehicleAmbiguous([target, *collisions.values()])
        # Credential мог быть отозван во время EnumDevices.
        await self.resolver.principal(ctx)
        return await self.memory.mutate_alias(principal, action, alias, operation_id, vehicle_id)


def memory_reply(ctx: "ToolContext", reply: str) -> str:
    """Положительное подтверждение записи выдаёт сервер только после успешной транзакции."""
    if ctx.alias_intent is None and not ctx.alias_results:
        return reply
    if ctx.alias_intent is not None and ctx.principal is None:
        return "Память не изменена: для персональных прозвищ сначала подтвердите личность."
    if not ctx.alias_results:
        return "Память не изменена: операция сохранения или удаления не была выполнена. Повторите команду."
    result = ctx.alias_results[-1]
    if result["status"] not in {"saved", "deleted", "superseded"}:
        # Отклонённый лишний tool не отменяет уже завершённую транзакцию этого ответа.
        result = next(
            (r for r in reversed(ctx.alias_results) if r["status"] in {"saved", "deleted", "superseded"}),
            result,
        )
    if result["status"] == "saved":
        return "Прозвище сохранено."
    if result["status"] == "deleted":
        return "Прозвище удалено."
    if result.get("vehicles"):
        variants = "; ".join(f"{v['label']} (ID: {v['id']})" for v in result["vehicles"])
        return f"Уточните машину: {variants}. Память не изменена."
    return result.get("message") or result.get("error") or "Изменение памяти не подтверждено."
