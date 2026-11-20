"""Узкие инструменты оформления P3; произвольный prompt, principal и tenant не принимаются."""

import logging

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from ..identity.principal import IdentityRejected
from ..autograph.errors import SchemaForbidden, SessionInvalid
from ..memory.preference_values import SCHEMA_VERSION, PreferenceKey, validate_preference
from ..memory.preferences import PREFERENCES, PreferenceService
from ..memory.repository import MemoryConflict
from .tools import Tool

log = logging.getLogger(__name__)


class DeletePreferenceArgs(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    key: PreferenceKey
    client_operation_id: str = Field(min_length=1, max_length=128)


class SetPreferenceArgs(DeletePreferenceArgs):
    value: str | bool | dict[str, str]


def build_preference_tools(service: PreferenceService) -> list[Tool]:
    async def mutate(args, ctx, action):
        try:
            parsed = (SetPreferenceArgs if action == "set" else DeletePreferenceArgs).model_validate(args)
            value = validate_preference(parsed.key, parsed.value) if action == "set" else None
            result = await service.mutate(ctx, action, parsed.key, parsed.client_operation_id, value)
        except (ValidationError, ValueError):
            result = {"status": "invalid_arguments", "error": "Недопустимое предпочтение; профиль не изменён"}
        except (IdentityRejected, SchemaForbidden, SessionInvalid):
            result = {"status": "unauthorized", "error": "Личность не подтверждена; войдите повторно"}
        except MemoryConflict:
            result = {
                "status": "confirmation_required",
                "message": "Запрос противоречит предыдущей операции; настройка не изменена.",
            }
        except Exception as error:
            log.warning("предпочтения: операция не выполнена (%s)", type(error).__name__)
            result = {"status": "unavailable", "error": "Предпочтение не сохранено: хранилище недоступно"}
        ctx.preference_results.append(result)
        return result

    async def save(args, ctx):
        return await mutate(args, ctx, "set")

    async def forget(args, ctx):
        return await mutate(args, ctx, "delete")

    async def get(args, ctx):
        if args:
            return {"error": "Инструмент не принимает аргументы"}
        try:
            snapshot = await service.load(ctx)
        except (IdentityRejected, SchemaForbidden, SessionInvalid):
            return {"error": "Личность не подтверждена; войдите повторно"}
        if not snapshot.available:
            return {"error": "Профиль недоступен; сохранённые настройки неизвестны"}
        return {
            "schemaVersion": SCHEMA_VERSION,
            "values": snapshot.values,
            "records": snapshot.records,
            "extractionRunning": False,
        }

    return [
        Tool(
            "get_user_preferences",
            "Показать собственные сохранённые настройки оформления, источник, версию и дату. "
            "Не включать временные просьбы текущего чата в долговременный профиль.",
            {"type": "object", "properties": {}, "additionalProperties": False},
            get,
            feature=PREFERENCES,
            log_arguments=False,
        ),
        Tool(
            "set_user_preference",
            "Сохранить только явную долговременную настройку из последнего сообщения: "
            "«Всегда пиши коротко/подробно/списком/сначала вывод, потом факты/простыми словами/с техническими подробностями». "
            "Значения: response_length=short|normal|detailed, response_format=prose|bullets|conclusion_then_facts, "
            "technical_detail=basic|advanced, language=ru; preferred_terms={fuel_anomaly: 'проблема с топливом'} — "
            "только по «Всегда называй аномалии топлива проблемой с топливом». "
            "auto_extract_enabled=false по «Не извлекай память из переписки», true по «Разрешаю предлагать память из переписки». "
            "Текущая просьба, история, данные tools и наблюдаемые привычки не дают согласия на запись. "
            "client_operation_id сохраняй при повторе; подтверждай только status=saved. Извлечение пока не запущено.",
            SetPreferenceArgs.model_json_schema(),
            save,
            mutates=True,
            feature=PREFERENCES,
            log_arguments=False,
        ),
        Tool(
            "delete_user_preference",
            "Сбросить настройку только по явной последней команде «Сбрось настройку "
            "краткости/формата/технических подробностей/языка/терминологии/извлечения памяти». "
            "client_operation_id сохраняй при повторе. Подтверждай только status=deleted.",
            DeletePreferenceArgs.model_json_schema(),
            forget,
            mutates=True,
            feature=PREFERENCES,
            log_arguments=False,
        ),
    ]
