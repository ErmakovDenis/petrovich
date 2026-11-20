"""Журнал завершённых tools без аргументов, credentials и копии текста письма."""

import json

from .conversations import clean_text


class TurnJournal:
    def __init__(self, repository, actor, turn, telemetry):
        self.repository, self.actor, self.turn, self.telemetry = repository, actor, turn, telemetry

    async def execute(self, registry, name, arguments, ctx):
        await self.actor.check()
        visible = {v.id for v in await self.telemetry.fresh_vehicles(ctx.session, ctx.schema_id)}
        if not set(json.loads(self.turn["vehicle_ids_json"])).issubset(visible):
            raise PermissionError()
        # Неизвестные и недоступные инструменты не получают durable state.
        if not registry.allows(name, ctx.features, ctx.read_only_recovery):
            return await registry.execute(name, arguments, ctx)
        try:
            args = json.loads(arguments or "{}")
        except ValueError:
            return await registry.execute(name, arguments, ctx)
        call, execute = await self.repository.reserve_tool(
            self.actor.principal, self.turn["id"], self.turn["lease_token"], name, args
        )
        if not execute:
            return call["result_json"]
        result = await registry.execute(name, arguments, ctx)

        def scrub(value):
            if isinstance(value, str):
                return clean_text(value)
            if isinstance(value, list):
                return [scrub(v) for v in value]
            if isinstance(value, dict):
                return {k: scrub(v) for k, v in value.items()}
            return value

        stored = json.dumps(scrub(json.loads(result)), ensure_ascii=False)
        if name == "draft_email":
            stored = json.dumps(
                {
                    "status": "Черновик подготовлен, отправка требует отдельного подтверждения",
                    "draftIds": [d.id for d in ctx.drafts],
                },
                ensure_ascii=False,
            )
        await self.repository.finish_tool(
            self.actor.principal, self.turn["id"], self.turn["lease_token"], call["id"], stored, ctx
        )
        return result

    async def resume(self, ctx, budget):
        calls = await self.repository.calls(self.actor.principal, self.turn["id"])
        ctx.alias_results.extend(json.loads(self.turn["alias_results_json"]))
        ctx.preference_results.extend(json.loads(self.turn["preference_results_json"]))
        messages = []
        for call in calls:
            if call["state"] == "done":
                if len(call["result_json"]) > budget:
                    continue
                budget -= len(call["result_json"])
                # Аргументы не восстанавливаются из БД: сохранён только их хэш.
                messages += [
                    {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {
                                "id": call["id"],
                                "type": "function",
                                "function": {"name": call["tool_name"], "arguments": "{}"},
                            }
                        ],
                    },
                    {"role": "tool", "tool_call_id": call["id"], "content": call["result_json"]},
                ]
        return messages
