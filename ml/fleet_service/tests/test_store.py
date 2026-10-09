"""Хранилище аномалий и решений (шаг 4): id стенда на канонической сетке, слияние эпизодов, доступ по схеме и машинам,
решения и их история, перенос решений с устройства, срок хранения, миграции, tools и контекст чата."""

import asyncio
import json
from datetime import datetime, timedelta
from urllib.parse import quote

import pytest
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy import inspect

from fleet_service.schemas.contract import Anomaly, MetricCategory, Severity
from fleet_service.store.db import create_db_engine, metadata
from fleet_service.store.migrate import downgrade, upgrade
from fleet_service.schemas.store import Resolution
from fleet_service.store.repository import AnomalyFilter, AnomalyRepository, Detection, stand_id

from .conftest import AUTH, FakeLLM, ask, test_settings, text, tool_call
from .fakes import FOREIGN_TOKEN, OTHER_TOKEN, SCHEMA_ID

OFFSET = 300
OTHER = {"Authorization": f"Bearer {OTHER_TOKEN}", "X-Schema-Id": SCHEMA_ID}
FOREIGN = {"Authorization": f"Bearer {FOREIGN_TOKEN}", "X-Schema-Id": "schema-2"}
OVERHEAT_0630 = "rule|overheat|veh-2|TemperatureCOOL|2026-09-16T01:30Z"


def scan(client, from_: str, to: str, vehicles: list[str] | None = ("veh-2",), headers=AUTH, offset=OFFSET) -> dict:
    body = {"from": from_, "to": to, "utcOffsetMinutes": offset}
    if vehicles is not None:
        body["vehicleIds"] = list(vehicles)
    r = client.post("/v1/anomalies/scan", json=body, headers=headers)
    assert r.status_code == 200, r.text
    return r.json()


def listing(client, headers=AUTH, **params) -> dict:
    r = client.get("/v1/anomalies", params={"utcOffsetMinutes": OFFSET, **params}, headers=headers)
    assert r.status_code == 200, r.text
    return r.json()


def resolve(client, anomaly_id: str, resolution, reason=None, headers=AUTH, user: str | None = None):
    h = {**headers, **({"X-User-Name": quote(user)} if user else {})}
    return client.post(f"/v1/anomalies/{quote(anomaly_id, safe='')}/resolve",
                       json={"resolution": resolution, "reason": reason, "utcOffsetMinutes": OFFSET}, headers=h)


# --- один id на событие ---

def test_same_event_gets_one_id_across_windows_and_times(make_client):
    """Разные окна (6 ч, 3 ч, сутки, с началом на чётной и нечётной минуте) — те же id и без дубликатов."""
    with make_client() as client:
        first = scan(client, "2026-09-16T06:00:00", "2026-09-16T12:00:00")
        ids = {a["id"] for a in first["results"][0]["anomalies"]}
        assert len(ids) == 6 and OVERHEAT_0630 in ids
        assert set(first["results"][0]["newIds"]) == ids

        again = [
            scan(client, "2026-09-16T06:01:00", "2026-09-16T09:01:00"),
            scan(client, "2026-09-15T12:07:00", "2026-09-16T12:07:00"),
            scan(client, "2026-09-16T05:59:59", "2026-09-16T11:00:13"),
        ]
        found = [{a["id"] for a in r["results"][0]["anomalies"]} for r in again]
        new = [r["results"][0]["newIds"] for r in again]
        assert len(found[0]) == 3 and found[0] <= ids and new[0] == []
        assert ids <= found[1] and len(found[1]) == 24 and len(new[1]) == 18
        assert len(found[2]) == 5 and found[2] <= ids and new[2] == []

        stored = listing(client, vehicleId="veh-2")
    # Сутки 15.09 12:07 — 16.09 12:07: по одному эпизоду в час, ни одного дубликата.
    assert stored["total"] == 24
    assert len({a["eventTime"] for a in stored["items"]}) == 24
    assert all(a["eventTime"].endswith(":30") for a in stored["items"])
    first_item = next(a for a in stored["items"] if a["id"] == OVERHEAT_0630)
    assert first_item["eventTime"] == "2026-09-16T06:30" and first_item["episodeEnd"] == "2026-09-16T06:39"


def test_episode_cut_by_window_start_merges_with_stored_one(make_client):
    """Окно началось посреди перегрева 06:30–06:39: продолжение не создаёт новую аномалию."""
    with make_client() as client:
        scan(client, "2026-09-16T06:00:00", "2026-09-16T07:00:00")
        cut = scan(client, "2026-09-16T06:35:00", "2026-09-16T08:00:00")["results"][0]
        stored = listing(client, vehicleId="veh-2")
    assert OVERHEAT_0630 in {a["id"] for a in cut["anomalies"]}
    assert cut["newIds"] == ["rule|overheat|veh-2|TemperatureCOOL|2026-09-16T02:30Z"]
    assert stored["total"] == 2


def test_cut_episode_seen_first_keeps_its_id_and_moves_start_earlier(make_client):
    with make_client() as client:
        cut = scan(client, "2026-09-16T06:35:00", "2026-09-16T07:00:00")["results"][0]
        full = scan(client, "2026-09-16T06:00:00", "2026-09-16T07:00:00")["results"][0]
        stored = listing(client, vehicleId="veh-2")
    cut_id = "rule|overheat|veh-2|TemperatureCOOL|2026-09-16T01:35Z"
    assert [a["id"] for a in cut["anomalies"]] == [cut_id]
    assert full["newIds"] == [] and [a["id"] for a in full["anomalies"]] == [cut_id]
    assert stored["total"] == 1 and stored["items"][0]["eventTime"] == "2026-09-16T06:30"


def test_canonical_grid_is_anchored_to_utc_epoch(make_client):
    with make_client(test_settings(scan_bucket_minutes=5)) as client:
        service = client.app.state.scan_service
        # UTC+5:30: местные 10:07 — это 04:37 UTC, вниз по 5 мин — 04:35 UTC = 10:05 местного.
        f, t = service.canonical_period(datetime(2026, 9, 16, 10, 7, 41), datetime(2026, 9, 16, 16, 3), 330)
        assert (f, t) == (datetime(2026, 9, 16, 10, 5), datetime(2026, 9, 16, 16, 0))
        r = client.post("/v1/anomalies/scan", headers=AUTH, json={
            "from": "2026-09-16T10:01:00", "to": "2026-09-16T10:04:00", "utcOffsetMinutes": OFFSET})
    assert r.status_code == 422 and "короче шага сетки" in r.json()["detail"]


def _anomaly(kind: str, start: datetime, severity=Severity.WARNING) -> Anomaly:
    return Anomaly(
        id=f"rule|{kind}|veh-1|P|{start:%Y-%m-%dT%H:%M}", vehicle_id="veh-1", vehicle_name="FAW №1",
        category=MetricCategory.ENGINE, parameter_name="P", parameter_caption="П", event_time=f"{start:%Y-%m-%dT%H:%M}",
        detected_at=0, severity=severity, title=kind, description="", source="Базовые правила",
    )


def test_merge_rules_for_adjacent_separate_and_drop_events(tmp_path):
    db = f"sqlite:///{tmp_path / 'merge.db'}"
    upgrade(db)
    store = AnomalyRepository(create_db_engine(db), timedelta(minutes=1), timedelta(minutes=60), timedelta(days=180))
    t0 = datetime(2026, 9, 16, 1, 0)
    m = timedelta(minutes=1)

    def det(kind, start, end, severity=Severity.WARNING):
        return Detection(_anomaly(kind, start, severity), start, end)

    async def run():
        first = await store.save("s", [det("brake", t0, t0 + 10 * m)])
        # Следующий интервал после конца — тот же эпизод (без разрыва); важность растёт.
        adjacent = await store.save("s", [det("brake", t0 + 11 * m, t0 + 20 * m, Severity.CRITICAL)])
        # Через интервал без условия — новый эпизод.
        separate = await store.save("s", [det("brake", t0 + 22 * m, t0 + 25 * m)])
        drops = await store.save("s", [det("drop", t0, t0), det("drop", t0 + 59 * m, t0 + 59 * m),
                                       det("drop", t0 + 120 * m, t0 + 120 * m)])
        return first, adjacent, separate, drops

    first, adjacent, separate, drops = asyncio.run(run())
    store.dispose()
    assert first[0][1] is True
    assert adjacent[0][0].id == first[0][0].id and adjacent[0][1] is False
    assert adjacent[0][0].severity == "CRITICAL" and adjacent[0][0].end_utc == t0 + 20 * m
    assert separate[0][1] is True
    # Падение уровня в пределах паузы правила (60 мин) — то же событие, позже — новое.
    assert [created for _, created in drops] == [True, False, True]


def test_episode_joining_two_records_merges_them_with_decision(tmp_path):
    db = f"sqlite:///{tmp_path / 'join.db'}"
    upgrade(db)
    store = AnomalyRepository(create_db_engine(db), timedelta(minutes=1), timedelta(minutes=60), timedelta(days=180))
    t0 = datetime(2026, 9, 16, 1, 0)
    m = timedelta(minutes=1)

    def det(start, end, severity=Severity.WARNING):
        return Detection(_anomaly("brake", start, severity), start, end)

    async def run():
        (first, _), = await store.save("s", [det(t0, t0 + 10 * m)])
        (second, _), = await store.save("s", [det(t0 + 40 * m, t0 + 60 * m, Severity.CRITICAL)])
        await store.resolve("s", second.id, Resolution.FALSE_ALARM, "Ошибка датчика", "Петров")
        # Новое окно показало, что 01:00–02:00 — один эпизод.
        (joined, created), = await store.save("s", [det(t0 + 5 * m, t0 + 45 * m)])
        everything = await store.query("s", AnomalyFilter(vehicle_ids=["veh-1"]), 10)
        return first, second, joined, created, everything, await store.history("s", first.id)

    first, second, joined, created, (total, rows), history = asyncio.run(run())
    store.dispose()
    assert created is False and total == 1 and rows[0].id == first.id == joined.id
    assert (joined.start_utc, joined.end_utc) == (t0, t0 + 60 * m)
    # Важность и решение слитой записи не теряются, история решений переходит к оставшейся.
    assert joined.severity == "CRITICAL" and joined.resolution == "FALSE_ALARM" and joined.resolved_by == "Петров"
    assert [d.user_name for d in history] == ["Петров"]


@pytest.mark.parametrize("text, expected", [
    ("2026-09-16T06:30", datetime(2026, 9, 16, 6, 30)),
    ("2026-09-16T01:30:00Z", datetime(2026, 9, 16, 6, 30)),
    ("2026-09-16T04:30:00+03:00", datetime(2026, 9, 16, 6, 30)),
    ("16.09.2026 06:30", None),
])
def test_event_time_from_analytics_is_read_tolerantly(text, expected):
    from fleet_service.store.scan import _local_time

    assert _local_time(text, OFFSET) == expected


def test_stand_id_marks_utc_and_keeps_kind_position():
    a = _anomaly("overheat", datetime(2026, 9, 16, 6, 30))
    sid = stand_id(a, datetime(2026, 9, 16, 1, 30))
    assert sid == "rule|overheat|veh-1|P|2026-09-16T01:30Z" and sid.split("|")[1] == "overheat"
    ml = a.model_copy(update={"id": "без-разделителей"})
    assert stand_id(ml, datetime(2026, 9, 16, 1, 30, 15)) == "ml||veh-1|P|2026-09-16T01:30:15Z"


# --- доступ, решения, отметка проверки ---

def test_user_sees_and_changes_only_own_schema_and_vehicles(make_client):
    with make_client() as client:
        scan(client, "2026-09-16T06:00:00", "2026-09-16T08:00:00", vehicles=None)
        assert listing(client)["total"] == 2
        # other-token: та же схема, но только veh-1 — аномалий veh-2 не видит и не меняет.
        assert listing(client, headers=OTHER)["total"] == 0
        assert resolve(client, OVERHEAT_0630, "CONFIRMED", headers=OTHER).status_code == 404
        # foreign-token: другая схема, где есть та же машина veh-2, — хранилище схемы-1 ему не видно.
        assert listing(client, headers=FOREIGN)["total"] == 0
        assert resolve(client, OVERHEAT_0630, "CONFIRMED", headers=FOREIGN).status_code == 404
        r = client.post("/v1/anomalies/scan", headers=OTHER, json={
            "vehicleIds": ["veh-2"], "from": "2026-09-16T06:00:00", "to": "2026-09-16T08:00:00",
            "utcOffsetMinutes": OFFSET})
        assert r.json()["results"] == [{"vehicleId": "veh-2", "ok": False, "error": "Машина не найдена или недоступна "
                                        "этому пользователю", "anomalies": [], "newIds": [], "modelsReady": None}]
        assert resolve(client, "rule|overheat|veh-2|TemperatureCOOL|2026-09-16T02:30Z", "CONFIRMED").status_code == 200


def test_resolve_records_who_and_when_and_history(make_client):
    with make_client() as client:
        scan(client, "2026-09-16T06:00:00", "2026-09-16T08:00:00")
        r = resolve(client, OVERHEAT_0630, "FALSE_ALARM", "Ошибка датчика", user="Петров")
        assert r.status_code == 200
        a = r.json()
        assert a["resolution"] == "FALSE_ALARM" and a["falseAlarmReason"] == "Ошибка датчика"
        assert a["resolvedBy"] == "Петров" and a["resolvedAt"] > 0 and a["eventTime"] == "2026-09-16T06:30"
        # Причина бывает только у ложной тревоги; null — вернуть в «ждут решения».
        assert resolve(client, OVERHEAT_0630, "CONFIRMED", "лишнее", user="Иванов").json()["falseAlarmReason"] is None
        assert listing(client, status="confirmed")["total"] == 1
        assert listing(client, status="open")["total"] == 1
        reopened = resolve(client, OVERHEAT_0630, None).json()
        assert reopened["resolution"] is None and reopened["resolvedBy"] is None
        # Приложение не сериализует null: без поля resolution — тоже возврат в «ждут решения».
        resolve(client, OVERHEAT_0630, "CONFIRMED")
        r = client.post(f"/v1/anomalies/{quote(OVERHEAT_0630, safe='')}/resolve", headers=AUTH,
                        json={"utcOffsetMinutes": OFFSET})
        assert r.status_code == 200 and r.json()["resolution"] is None
        assert listing(client, status="open")["total"] == 2
        history = asyncio.run(client.app.state.store.history(SCHEMA_ID, OVERHEAT_0630))
    assert [(d.resolution and d.resolution.value, d.user_name) for d in history] == [
        ("FALSE_ALARM", "Петров"), ("CONFIRMED", "Иванов"), (None, None), ("CONFIRMED", None), (None, None)]


def test_list_filters_and_limit(make_client):
    with make_client(test_settings(anomalies_max_limit=3)) as client:
        scan(client, "2026-09-16T00:00:00", "2026-09-16T12:00:00", vehicles=None)
        everything = listing(client, limit=100)
        assert everything["total"] == 12 and len(everything["items"]) == 3
        assert everything["items"][0]["eventTime"] == "2026-09-16T11:30"
        assert listing(client, severity="WARNING")["total"] == 0
        assert listing(client, severity=["CRITICAL", "WARNING"])["total"] == 12
        # Период — по пересечению с эпизодом: 06:35–06:36 попадает в перегрев 06:30–06:39.
        window = listing(client, **{"from": "2026-09-16T06:35:00", "to": "2026-09-16T06:36:00"})
        assert [a["eventTime"] for a in window["items"]] == ["2026-09-16T06:30"]
        assert listing(client, vehicleId="veh-1")["total"] == 0


def test_last_scan_moves_only_when_a_vehicle_answered(make_client, fakes):
    with make_client(test_settings(autograph_retry_delay_seconds=0)) as client:
        fakes.state.trip_tables_down = {"veh-2"}
        failed = scan(client, "2026-09-16T06:00:00", "2026-09-16T08:00:00")
        assert failed["results"][0]["ok"] is False and "AutoGRAPH" in failed["results"][0]["error"]
        assert failed["lastScanAt"] is None and listing(client)["lastScanAt"] is None
        partial = scan(client, "2026-09-16T06:00:00", "2026-09-16T08:00:00", vehicles=["veh-1", "veh-2"])
        assert [r["ok"] for r in partial["results"]] == [True, False]
        assert partial["lastScanAt"] is not None


def test_expired_token_during_scan_is_401_not_vehicle_error(make_client):
    """Токен истёк между проверкой сессии и загрузкой данных: 401 (приложение войдёт заново), а не ok=false."""
    from fleet_service.autograph.errors import SessionInvalid

    with make_client() as client:
        async def expired(*_args, **_kwargs):
            raise SessionInvalid()

        client.app.state.check_service.check = expired
        r = client.post("/v1/anomalies/scan", headers=AUTH, json={
            "from": "2026-09-16T06:00:00", "to": "2026-09-16T08:00:00", "utcOffsetMinutes": OFFSET})
        assert r.status_code == 401
        assert listing(client)["lastScanAt"] is None


def test_store_survives_app_restart(make_client):
    with make_client() as client:
        scan(client, "2026-09-16T06:00:00", "2026-09-16T08:00:00")
        resolve(client, OVERHEAT_0630, "CONFIRMED", user="Петров")
    with make_client() as client:
        stored = listing(client)
    assert stored["total"] == 2 and stored["lastScanAt"] is not None
    assert next(a for a in stored["items"] if a["id"] == OVERHEAT_0630)["resolvedBy"] == "Петров"


def test_retention_removes_old_events_and_their_decisions(make_client):
    with make_client() as client:
        scan(client, "2026-09-16T06:00:00", "2026-09-16T08:00:00")
        resolve(client, OVERHEAT_0630, "CONFIRMED")
        store = client.app.state.store
        removed = asyncio.run(store.purge(datetime(2026, 9, 16, 2, 0) + timedelta(days=180)))
        assert removed == 1
        assert [a["id"] for a in listing(client)["items"]] == ["rule|overheat|veh-2|TemperatureCOOL|2026-09-16T02:30Z"]
        assert asyncio.run(store.history(SCHEMA_ID, OVERHEAT_0630)) == []


# --- перенос решений с устройства ---

def _local_item(local_id: str, event: str, resolution=None, reason=None, vehicle="veh-2", kind="overheat",
                parameter="TemperatureCOOL") -> dict:
    return {"localId": local_id, "vehicleId": vehicle, "vehicleName": "Урал NEXT А001АА", "kind": kind,
            "parameterName": parameter, "title": "Перегрев двигателя", "eventTime": event,
            "resolution": resolution, "reason": reason}


def test_import_matches_local_decisions_and_reports_unmatched(make_client):
    items = [
        # id устройства на 2-минутной сетке — время совпадает или отличается в пределах допуска.
        _local_item("rule|overheat|veh-2|TemperatureCOOL|2026-09-16T06:30", "2026-09-16T06:30:00", "CONFIRMED"),
        _local_item("rule|overheat|veh-2|TemperatureCOOL|2026-09-16T07:32", "2026-09-16T07:32:00", "FALSE_ALARM",
                    "Ошибка датчика"),
        _local_item("rule|overheat|veh-2|TemperatureCOOL|2026-09-16T08:30", "2026-09-16T08:30:00"),
        # Такого события стенд не находит, машина veh-3 недоступна.
        _local_item("rule|drain|veh-2|FuelDrainVolume|2026-09-16T07:00", "2026-09-16T07:00:00", "CONFIRMED",
                    kind="drain", parameter="FuelDrainVolume"),
        _local_item("rule|overheat|veh-3|TemperatureCOOL|2026-09-16T06:30", "2026-09-16T06:30:00", "CONFIRMED",
                    vehicle="veh-3"),
        _local_item("rule|overheat|veh-2|TemperatureCOOL|2026-09-16T09:15", "2026-09-16T09:15:00"),
    ]
    with make_client() as client:
        h = {**AUTH, "X-User-Name": quote("Петров")}
        r = client.post("/v1/anomalies/import", json={"utcOffsetMinutes": OFFSET, "items": items}, headers=h)
        assert r.status_code == 200, r.text
        report = r.json()
        stored = {a["id"]: a for a in listing(client)["items"]}
        again = client.post("/v1/anomalies/import", json={"utcOffsetMinutes": OFFSET, "items": items[:2]},
                            headers=AUTH).json()
        history = asyncio.run(client.app.state.store.history(SCHEMA_ID, OVERHEAT_0630))
    assert report["decisions"] == 4 and report["applied"] == 2 and report["alreadyResolved"] == 0
    assert report["restored"] == 1 and report["notFound"] == 1 and report["scanErrors"] == []
    assert {(u["localId"].split("|")[2], u["why"]) for u in report["unmatched"]} == {
        ("veh-2", "стенд не нашёл это событие при проверке того же периода"),
        ("veh-3", "машина недоступна этому пользователю на стенде"),
    }
    assert stored[OVERHEAT_0630]["resolution"] == "CONFIRMED" and stored[OVERHEAT_0630]["resolvedBy"] == "Петров"
    assert stored["rule|overheat|veh-2|TemperatureCOOL|2026-09-16T02:30Z"]["falseAlarmReason"] == "Ошибка датчика"
    assert stored["rule|overheat|veh-2|TemperatureCOOL|2026-09-16T03:30Z"]["resolution"] is None
    # Повторный перенос не перезаписывает решения, уже принятые на стенде.
    assert again["applied"] == 0 and again["alreadyResolved"] == 2
    assert [d.origin for d in history] == ["import"]


def test_import_reports_period_that_could_not_be_checked(make_client, fakes):
    fakes.state.trip_tables_down = {"veh-2"}
    with make_client(test_settings(autograph_retry_delay_seconds=0)) as client:
        report = client.post("/v1/anomalies/import", headers=AUTH, json={"utcOffsetMinutes": OFFSET, "items": [
            _local_item("x", "2026-09-16T06:30:00", "CONFIRMED")]}).json()
    assert report["applied"] == 0 and len(report["scanErrors"]) == 1
    assert report["unmatched"][0]["why"].startswith("период не удалось проверить: AutoGRAPH")


def test_import_size_limit(make_client):
    with make_client(test_settings(import_max_items=1)) as client:
        r = client.post("/v1/anomalies/import", headers=AUTH, json={"utcOffsetMinutes": OFFSET, "items": [
            _local_item("a", "2026-09-16T06:30:00"), _local_item("b", "2026-09-16T07:30:00")]})
    assert r.status_code == 422 and "не больше 1" in r.json()["detail"]


# --- ассистент ---

def test_assistant_lists_anomalies_and_tells_decision(make_client, fakes):
    with make_client() as client:
        empty = client.post("/v1/chat", json=ask("Какие аномалии за сутки?"), headers=AUTH).json()["reply"]
        scan(client, "2026-09-16T06:00:00", "2026-09-16T08:00:00")
        resolve(client, OVERHEAT_0630, "FALSE_ALARM", "Ошибка датчика", user="Петров")
        decision = client.post("/v1/chat", json=ask("Что решили по этой аномалии?", anomalyId=OVERHEAT_0630),
                               headers=AUTH).json()["reply"]
        missing = client.post("/v1/chat", json=ask("Что решили по этой аномалии?", anomalyId="rule|нет"),
                              headers=AUTH)
    assert "Проверок с сохранением по этой схеме ещё не было" in empty
    assert decision == "Урал NEXT А001АА, Перегрев двигателя: ложная тревога (Ошибка датчика), решил Петров."
    context = [m["content"] for m in fakes.state.llm_requests[-1]["messages"] if m["role"] == "system"]
    assert any("в хранилище стенда её нет" in c for c in context)
    assert missing.status_code == 200


def test_list_anomalies_tool_counts_total_and_truncates(make_client):
    from fleet_service.agent.tools import ToolContext

    llm = FakeLLM(tool_call("c1", "list_anomalies", '{"from": "2026-09-16T00:00", "to": "2026-09-16T12:00"}'),
                  text("готово"))
    with make_client(test_settings(tool_max_anomalies=2), llm=llm) as client:
        scan(client, "2026-09-16T00:00:00", "2026-09-16T12:00:00", vehicles=None)
        r = client.post("/v1/chat", json=ask("Какие аномалии?"), headers=AUTH)
        assert r.status_code == 200
        registry = client.app.state.agent._tools
        ctx = ToolContext("valid-token", SCHEMA_ID, OFFSET)
        result = asyncio.run(registry.execute("list_anomalies", '{"from": "2026-09-16T00:00", "to": "2026-09-16T12:00", '
                                              '"status": "open", "severity": ["CRITICAL"]}', ctx))
        bad = asyncio.run(registry.execute("list_anomalies", '{"status": "всё"}', ctx))
    data = json.loads(result)
    assert data["total"] == 12 and len(data["anomalies"]) == 2 and data["truncated"] is True
    assert data["anomalies"][0]["status"] == "ждёт решения" and data["lastScanAt"] is not None
    assert "error" in json.loads(bad)
    tool_message = next(m for m in llm.calls[1][0] if m["role"] == "tool")
    assert '"total": 12' in tool_message["content"]


# --- миграции ---

def test_migrations_match_tables_and_downgrade(tmp_path):
    db = f"sqlite:///{tmp_path / 'm.db'}"
    upgrade(db)
    engine = create_db_engine(db)
    with engine.connect() as conn:
        diff = compare_metadata(MigrationContext.configure(conn), metadata)
    assert diff == []
    downgrade(db, "base")
    assert set(inspect(engine).get_table_names()) == {"alembic_version"}
    engine.dispose()


@pytest.mark.parametrize("value, expected", [(None, None), ("", None), (quote("Иван Петров"), "Иван Петров"),
                                             ("  ivanov  ", "ivanov")])
def test_user_name_header(value, expected):
    from fleet_service.api.deps import user_name

    assert user_name(value) == expected
