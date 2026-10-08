"""Порт TripTablesMapper / AutoGraphParameters: кейсы TripTablesMapperTest приложения и граничные случаи разбора."""

import math
from datetime import datetime, timedelta

import pytest

from fleet_service.autograph.errors import TripTablesTooLarge
from fleet_service.schemas.contract import MetricCategory, Vehicle
from fleet_service.telemetry.mapper import TripTablesBuilder, bucket_for, epoch_second, parse_number_string
from fleet_service.telemetry.parameters import Aggregation, RParameter, select

VEHICLE = Vehicle(id="v1", name="FAW №1")
FROM = datetime(2026, 9, 16, 10, 0)


def builder(params: list[RParameter], minutes: int, bucket: timedelta | None = timedelta(minutes=1), **kw):
    selected, aggregation = select(params)
    return TripTablesBuilder(VEHICLE, FROM, FROM + timedelta(minutes=minutes), selected, aggregation, bucket, **kw)


@pytest.mark.parametrize("period, minutes", [
    (timedelta(hours=6), 1), (timedelta(hours=6, minutes=1), 2), (timedelta(hours=24), 2),
    (timedelta(days=3), 5), (timedelta(days=7), 15),
])
def test_bucket_for(period, minutes):
    assert bucket_for(period) == timedelta(minutes=minutes)


def test_epoch_second_matches_datetime():
    for s in ["2026-09-16T10:00:05", "2026-09-16T23:59:59.123", "2026-09-16T05:00:05Z", "2024-02-29T12:30:00",
              "2026-01-01T00:00:00+05:00", "1999-12-31T23:59:59"]:
        expected = int((datetime.fromisoformat(s[:19]) - datetime(1970, 1, 1)).total_seconds())
        assert epoch_second(s) == expected, s
    for s in ["2026-02-30T10:00:00", "2026-13-01T10:00:00", "2026-09-16 10:00:00", "00:00:10", "20260916-1000",
              "2026-09-16T10:00", "２０26-09-16T10:00:00"]:
        assert epoch_second(s) is None, s


@pytest.mark.parametrize("text, value", [
    ("20", 20.0), ("-3", -3.0), ("12,5", 12.5), ("1e3", 1000.0), ("2.5f", 2.5), ("7d", 7.0),
    ("00:00:10", math.nan), ("abc", math.nan), ("", math.nan), (" 5", math.nan), ("1_000", math.nan),
    ("-inf", math.nan), ("-Infinity", -math.inf),
])
def test_number_strings_like_java(text, value):
    got = parse_number_string(text)
    assert (math.isnan(got) and math.isnan(value)) or got == value


def test_aggregates_into_buckets_and_drops_duplicates_and_empty_sensors():
    """aggregatesIntoBucketsAndDropsDuplicatesAndEmptySensors из TripTablesMapperTest."""
    sample = b"""{"v1":{"ID":"v1","Trips":[{"Index":0,
      "DT":["2026-09-16T10:00:05","2026-09-16T10:00:05","2026-09-16T10:00:35","2026-09-16T10:01:10","2026-09-16T10:03:10"],
      "Values":[
        {"Values":[200.0,200.0,199.0,198.0,150.0],"Name":"TankMainFuelLevel"},
        {"Name":"FL1","Values":[200.0,200.0,199.0,198.0,150.0]},
        {"Values":[1,1,0,1,1],"Name":"Power"},
        {"Name":"TemperatureOIL","Values":[0.0,0.0,0.0,0.0,0.0]},
        {"Name":"DIgnition","Values":[true,true,false,true,true]}]}]}}"""
    b = builder([
        RParameter("TankMainFuelLevel", "Уровень", unit="л", return_type=4), RParameter("FL1", return_type=4),
        RParameter("Power", return_type=0), RParameter("TemperatureOIL", return_type=4),
        RParameter("DIgnition", return_type=0), RParameter("DIgnitionOnParks", return_type=6),
    ], minutes=4)
    b.read_bytes(sample)
    result = b.build()
    fuel = result.telemetry.tables[MetricCategory.FUEL]
    assert len(fuel.timestamps) == 5
    assert [c.parameter.name for c in fuel.columns] == ["TankMainFuelLevel"]  # FL1 — дубликат
    assert fuel.columns[0].values == [199.7, 198.0, None, 150.0, None]
    power = next(c for c in result.telemetry.tables[MetricCategory.POWER].columns if c.parameter.name == "Power")
    assert power.values[0] == 0.0  # MIN: пропадание питания не усредняется
    assert MetricCategory.ENGINE not in result.telemetry.tables  # всегда 0 — датчик не подключён
    # TemperatureOIL — весь период 0 (не «нет данных»); дубликат FL1 не попадает ни туда, ни туда.
    assert [p.name for p in result.zero] == ["TemperatureOIL"] and result.empty == []


def test_values_before_dt_are_buffered_and_chunk_borders_do_not_matter():
    data = b"""{"v1":{"Trips":[{"Values":[{"Name":"Speed","Values":[10,"20",null,"00:00:10",{"x":[1]}]}],
        "DT":["2026-09-16T10:00:00","2026-09-16T10:00:30","2026-09-16T10:01:00","2026-09-16T10:01:30"]}]}}"""
    for size in (1, 7, len(data)):
        b = builder([RParameter("Speed", "Текущая", unit="км/ч", return_type=4)], minutes=2)
        reader = b.reader()
        for i in range(0, len(data), size):
            reader.feed(data[i:i + size])
        reader.close()
        assert b.build().telemetry.tables[MetricCategory.MOTION].columns[0].values == [15.0, None, None]


def test_points_outside_period_and_unknown_structure_are_ignored():
    data = """{"v1":{"Trips":[{"DT":["2026-09-16T09:59:59","2026-09-16T10:00:10","2026-09-16T10:03:00",123],
        "Values":[{"Name":"Speed","Values":[99,10,99,99]},{"Name":"Unknown","Values":[1,2,3,4]}]}],"Extra":{"Trips":1}},
        "v2":"не объект","v3":{"Trips":"не массив"}}""".encode()
    b = builder([RParameter("Speed", return_type=4)], minutes=2)
    b.read_bytes(data)
    assert b.build().telemetry.tables[MetricCategory.MOTION].columns[0].values == [10.0, None, None]


def test_nonzero_aggregation_treats_zero_as_missing_and_rounds_half_up():
    data = b"""{"v1":{"Trips":[{"DT":["2026-09-16T10:00:00","2026-09-16T10:00:20","2026-09-16T10:00:40",
        "2026-09-16T10:01:00","2026-09-16T10:01:30"],
        "Values":[{"Name":"BattaryVOLTAGE","Values":[0,27.25,27.3,0,0]},
                  {"Name":"PressureVSBC1","Values":[0,8.05,7.95,0,0]}]}]}}"""
    b = builder([RParameter("BattaryVOLTAGE", return_type=4), RParameter("PressureVSBC1", return_type=4)], minutes=1)
    b.read_bytes(data)
    tables = b.build().telemetry.tables
    voltage = tables[MetricCategory.POWER].columns[0].values
    brakes = tables[MetricCategory.MOTION].columns[0].values
    assert voltage == [27.3, None]  # (27.25+27.3)/2 = 27.275 → 27.3; нули — «нет данных»
    assert brakes == [8.0, None]  # MIN_NONZERO: 7.95 → 8.0 (половина — вверх)


def test_infinite_values_round_like_java():
    """Сбойная «-Infinity» не роняет разбор: Math.round(-∞) в Kotlin — Long.MIN_VALUE; «Infinity» начинается не с
    цифры и не с «-» — не число, как в readNumber. Числовой литерал вне double (1e999) yajl отвергает как ошибку
    разбора — стенд ответит «некорректный ответ»."""
    data = b"""{"v1":{"Trips":[{"DT":["2026-09-16T10:00:00","2026-09-16T10:00:20","2026-09-16T10:01:00"],
        "Values":[{"Name":"Speed","Values":["-Infinity",5,7]},
                  {"Name":"TemperatureCOOL","Values":["Infinity",1,2]}]}]}}"""
    b = builder([RParameter("Speed", return_type=4), RParameter("TemperatureCOOL", return_type=4)], minutes=1)
    b.read_bytes(data)
    tables = b.build().telemetry.tables
    assert tables[MetricCategory.MOTION].columns[0].values == [(-(2**63)) / 10.0, 7.0]
    assert tables[MetricCategory.ENGINE].columns[0].values == [1.0, 2.0]


def test_oversized_response_is_rejected():
    dts = ",".join(['"2026-09-16T10:00:00"'] * 11)
    b = builder([RParameter("Speed", return_type=4)], minutes=60, max_points=10)
    with pytest.raises(TripTablesTooLarge):
        b.read_bytes(f'{{"v1":{{"Trips":[{{"DT":[{dts}]}}]}}}}'.encode())
    values = ",".join(["1"] * 11)
    b = builder([RParameter("Speed", return_type=4)], minutes=60, max_points=10)
    with pytest.raises(TripTablesTooLarge):
        b.read_bytes(f'{{"v1":{{"Trips":[{{"Values":[{{"Name":"Speed","Values":[{values}]}}]}}]}}}}'.encode())


def test_select_classifies_nonstandard_device_by_keywords():
    params = [
        RParameter("LLS_A", "Уровень в баке", unit="л", return_type=4),
        RParameter("EngRPM", "Обороты двигателя", return_type=4),
        RParameter("Vbat", "Напряжение бортсети", unit=" ", return_type=4),
        RParameter("EngHours", "Моточасы", return_type=6),
        RParameter("Misc", None, alias="odometer", return_type=4),
    ]
    selected, aggregation = select(params)
    assert [(p.name, p.category) for p in selected] == [
        ("Vbat", MetricCategory.POWER), ("LLS_A", MetricCategory.FUEL),
        ("EngRPM", MetricCategory.ENGINE), ("Misc", MetricCategory.MOTION),
    ]
    assert selected[0].unit is None  # пустая единица — нет единицы
    assert selected[3].caption == "Misc"
    assert aggregation["Vbat"] is Aggregation.MEAN_NONZERO and aggregation["LLS_A"] is Aggregation.MEAN
