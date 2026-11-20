from dataclasses import dataclass


@dataclass(frozen=True)
class Principal:
    tenant_id: str
    principal_id: str
    allowed_schema_ids: frozenset[str]
    source: str = "stand_bound"
    verified: bool = True


class IdentityRejected(Exception):
    """Credential отсутствует, истёк, отозван или принадлежит другой сессии/схеме."""
