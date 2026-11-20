"""Один точный resolver для всех tools; прозвище никогда не расширяет права AutoGRAPH."""

from typing import TYPE_CHECKING

from ..identity.principal import IdentityRejected
from ..schemas.contract import Vehicle
from ..telemetry.service import TelemetryService, VehicleNotFound
from .repository import MemoryRepository

if TYPE_CHECKING:
    from ..agent.tools import ToolContext


class MemoryUnavailable(Exception):
    def __init__(self):
        super().__init__("Память недоступна; сохранение или чтение не выполнено")


class VehicleAmbiguous(Exception):
    def __init__(self, vehicles: list[Vehicle]):
        super().__init__("Название неоднозначно; уточните машину по ID из списка доступных машин")
        self.vehicles = vehicles


def vehicle_label(vehicle: Vehicle) -> str:
    if not vehicle.plate:
        return vehicle.name
    name, plate = vehicle.name.replace(" ", "").lower(), vehicle.plate.replace(" ", "").lower()
    if plate in name or name in plate:
        return vehicle.name
    return f"{vehicle.name} {vehicle.plate}"


def official_matches(vehicles: list[Vehicle], reference: str) -> list[Vehicle]:
    # ID имеет приоритет перед всеми текстовыми именами. Имена/номера/label — равноправные точные ссылки.
    by_id = [v for v in vehicles if v.id == reference]
    if by_id:
        return by_id
    needle = reference.strip().lower()
    return [
        v
        for v in vehicles
        if needle
        and needle
        in {
            v.name.strip().lower(),
            (v.plate or "").strip().lower(),
            vehicle_label(v).strip().lower(),
        }
    ]


class VehicleResolver:
    def __init__(self, telemetry: TelemetryService, memory: MemoryRepository | None = None):
        self.telemetry = telemetry
        self.memory = memory

    async def principal(self, ctx: "ToolContext"):
        principal = ctx.principal
        if principal is None or not principal.verified or principal.tenant_id != ctx.schema_id:
            raise IdentityRejected()
        if ctx.check_principal is not None:
            fresh = await ctx.check_principal()
            if fresh != principal:
                raise IdentityRejected()
        return principal

    async def vehicles(self, ctx: "ToolContext") -> list[Vehicle]:
        if self.memory is not None and ctx.principal is not None:
            await self.principal(ctx)
            return await self.telemetry.fresh_vehicles(ctx.session, ctx.schema_id)
        return await self.telemetry.vehicles(ctx.session, ctx.schema_id)

    async def alias(self, ctx: "ToolContext", reference: str) -> dict | None:
        if self.memory is None or ctx.principal is None:
            return None
        principal = await self.principal(ctx)
        try:
            return await self.memory.find_alias(principal, reference)
        except (IdentityRejected, ValueError):
            raise
        except Exception:
            # SQLAlchemy включает параметры запроса в исключения; не отдаём их реестру tools и логам.
            raise MemoryUnavailable() from None

    async def aliases(self, ctx: "ToolContext", vehicles: list[Vehicle]) -> dict[str, list[str]]:
        if self.memory is None or ctx.principal is None:
            return {}
        principal = await self.principal(ctx)
        try:
            rows = await self.memory.visible_aliases(principal, {v.id for v in vehicles})
        except IdentityRejected:
            raise
        except Exception:
            raise MemoryUnavailable() from None
        result: dict[str, list[str]] = {}
        for row in rows:
            result.setdefault(row["entity_id"], []).append(row["alias"])
        return result

    async def resolve(self, ctx: "ToolContext", reference: str) -> str:
        vehicles = await self.vehicles(ctx)
        matches = official_matches(vehicles, reference)
        # Точный ID не может быть переопределён личным прозвищем.
        if len(matches) == 1 and matches[0].id == reference:
            return matches[0].id
        alias = await self.alias(ctx, reference)
        if alias is not None:
            target = next((v for v in vehicles if v.id == alias["entity_id"]), None)
            if target is None:
                # Не выдаём ни старый ID, ни имя закрытой машины и не выбираем чужой официальный label.
                raise VehicleNotFound()
            if all(v.id != target.id for v in matches):
                matches.append(target)
        if not matches:
            raise VehicleNotFound()
        if len(matches) != 1:
            raise VehicleAmbiguous(matches)
        return matches[0].id
