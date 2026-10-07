package ru.petrovich.telemetry

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import ru.petrovich.telemetry.data.AutoGraphParameters
import ru.petrovich.telemetry.data.TripTablesMapper
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.api.RParameter
import java.io.File
import java.time.Duration
import java.time.LocalDateTime

/**
 * Образцы ответов GetTripTables и запись эталонов «ответ → VehicleTelemetry» для сверки с Python-портом
 * на стенде (ml/fleet_service/tests/test_golden.py). Формат эталона `<имя>.case.json`:
 * `{vehicle, from, to, bucketSeconds|null, parameters: [параметры EnumParameters], inputs: [файлы ответов], expected}`.
 */
object TripTablesGolden {

    // Формат как в ответе GetTripTables (у части колонок «Values» раньше «Name», как в реальном API): повторяющееся время, bool, строки-интервалы, 0 у неподключённых датчиков.
    val SAMPLE = """
        {"v1":{"ID":"v1","Name":"FAW №1","Serial":1,"Trips":[{"Index":0,"SD":"2026-09-16T05:00:05Z","ED":"2026-09-16T05:02:00Z",
          "DT":["2026-09-16T10:00:05","2026-09-16T10:00:05","2026-09-16T10:00:35","2026-09-16T10:01:10","2026-09-16T10:03:10"],
          "Values":[
            {"Values":[200.0,200.0,199.0,198.0,150.0],"Name":"TankMainFuelLevel","Caption":"Уровень","Unit":"л"},
            {"Name":"FL1","Caption":"ДУТ 1 шасси","Unit":"л","Values":[200.0,200.0,199.0,198.0,150.0]},
            {"Values":[1,1,0,1,1],"Name":"Power","Caption":"Питание"},
            {"Name":"TemperatureOIL","Caption":"Температура масла","Unit":"°C","Values":[0.0,0.0,0.0,0.0,0.0]},
            {"Name":"DIgnition","Caption":"Зажигание","Values":[true,true,false,true,true]}
          ]}]}}
    """.trimIndent()

    val SAMPLE_PARAMS = listOf(
        RParameter("TankMainFuelLevel", "Уровень", unit = "л", returnType = 4),
        RParameter("FL1", "ДУТ 1 шасси", unit = "л", returnType = 4),
        RParameter("Power", "Питание", returnType = 0),
        RParameter("TemperatureOIL", "Температура масла", unit = "°C", returnType = 4),
        RParameter("DIgnition", "Зажигание", returnType = 0),
        RParameter("DIgnitionOnParks", "Накоп. МЧ ост.", returnType = 6),
    )

    val VALUES_BEFORE_DT = """{"v1":{"Trips":[{"Values":[{"Name":"Speed","Values":[10,"20",null,"00:00:10"]}],
            "DT":["2026-09-16T10:00:00","2026-09-16T10:00:30","2026-09-16T10:01:00","2026-09-16T10:01:30"]}]}}"""

    val VALUES_BEFORE_DT_PARAMS = listOf(RParameter("Speed", "Текущая", unit = "км/ч", returnType = 4))

    /** Каталог репозитория, в котором лежит testdata/ (тесты gradle запускаются из app/). */
    fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "testdata").isDirectory }

    /** Строит VehicleTelemetry, как AutoGraphTelemetryRepository: select() по параметрам и разбор ответов по очереди. */
    fun build(
        vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime, bucket: Duration?,
        parameters: List<RParameter>, inputs: List<File>,
    ): VehicleTelemetry {
        val (selected, aggregation) = AutoGraphParameters.select(parameters)
        val builder = if (bucket != null) {
            TripTablesMapper.Builder(vehicle, from, to, selected, aggregation, bucket)
        } else {
            TripTablesMapper.Builder(vehicle, from, to, selected, aggregation)
        }
        inputs.forEach { f -> f.bufferedReader().use { builder.read(it) } }
        return builder.build()
    }

    /** JSON эталона; [inputNames] — как записать пути ответов (относительно файла эталона или абсолютные). */
    fun caseJson(
        vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime, bucket: Duration?,
        parameters: List<RParameter>, inputNames: List<String>, expected: VehicleTelemetry,
    ): JsonElement = buildJsonObject {
        put("vehicle", ApiFactory.json.encodeToJsonElement(Vehicle.serializer(), vehicle))
        put("from", JsonPrimitive(from.toString()))
        put("to", JsonPrimitive(to.toString()))
        put("bucketSeconds", bucket?.let { JsonPrimitive(it.seconds) } ?: JsonNull)
        put("parameters", ApiFactory.json.encodeToJsonElement(ListSerializer(RParameter.serializer()), parameters))
        putJsonArray("inputs") { inputNames.forEach { add(JsonPrimitive(it)) } }
        put("expected", ApiFactory.json.encodeToJsonElement(VehicleTelemetry.serializer(), expected))
    }

    /** JSON с отступами, но массивы чисел и строк — в одну строку: эталон остаётся небольшим и читаемым в diff. */
    fun format(element: JsonElement, indent: String = ""): String {
        val inner = "$indent  "
        return when (element) {
            is JsonObject -> if (element.isEmpty()) "{}" else element.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
                "$inner${JsonPrimitive(k)}: ${format(v, inner)}"
            }
            is JsonArray -> when {
                element.isEmpty() -> "[]"
                element.all { it is JsonPrimitive } -> element.joinToString(", ", "[", "]")
                else -> element.joinToString(",\n", "[\n", "\n$indent]") { inner + format(it, inner) }
            }
            else -> element.toString()
        }
    }
}
