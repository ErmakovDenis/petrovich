"""P0: неисправный ответ провайдера не даёт прав на выдуманные строковые схемы."""

from fastapi.responses import JSONResponse

from fleet_service.autograph.schema_ids import parse_schema_ids
from .conftest import AUTH
from .test_identity_memory import bind, enabled


def test_only_actual_primitive_schema_ids_are_authorities():
    assert parse_schema_ids([{"ID": "schema-1"}, {"ID": 0}, {"ID": "None"}, {"ID": None}, {},
                             {"ID": True}, {"ID": {"bad": "value"}}, {"ID": " "}, {"ID": "x\ny"}]) == {"schema-1", "0", "None"}


def test_malformed_enum_schemas_cannot_authorize_personal_or_legacy_api(make_client, fakes):
    @fakes.middleware("http")
    async def malformed(request, call_next):
        if request.url.path.endswith("/EnumSchemas"):
            return JSONResponse([{"ID": None}, {"ID": True}, {"ID": {"bad": "value"}}])
        return await call_next(request)
    with make_client(enabled()) as client:
        for schema in ("None", "True", "{'bad': 'value'}"):
            assert bind(client, schemaId=schema).status_code == 403
            assert client.get("/v1/vehicles", headers={**AUTH, "X-Schema-Id": schema}).status_code == 403
