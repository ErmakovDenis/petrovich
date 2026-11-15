package ru.petrovich.telemetry.anomaly

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.api.PetrovichAiApi
import ru.petrovich.telemetry.data.api.VehicleOut
import java.io.IOException

/**
 * Сопоставление машин приложения (id и имена AutoGRAPH) с машинами API детекции (свои числовые id).
 * Список машин API кэшируется на [CACHE_TTL_MS]: за одну сверку парка он запрашивается один раз, а не на каждую машину.
 *
 * Соответствие ищется в порядке: тот же строковый id → госномер целиком равен имени → госномер в имени
 * («Урал NEXT А001АА») → модель в имени. Ничего не подошло — [StandException] «машина не найдена»;
 * в API вообще нет машин — [StandException] с подсказкой, что на стороне API не загружены данные.
 */
class ApiVehicleResolver(
    private val api: PetrovichAiApi,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var cache: Pair<Long, List<VehicleOut>>? = null

    suspend fun resolve(vehicle: Vehicle): Int {
        val vehicles = cached()
        if (vehicles.isEmpty()) {
            throw StandException("в API аномалий нет машин — на стороне API не загружены данные (нужен ingest файла или sync AutoGRAPH)")
        }
        val name = normalize(vehicle.name)
        vehicles.firstOrNull { it.id.toString() == vehicle.id }?.let { return it.id }
        vehicles.firstOrNull { normalize(it.plate) == name }?.let { return it.id }
        vehicles.firstOrNull { normalize(it.plate).isNotBlank() && normalize(it.plate) in name }?.let { return it.id }
        vehicles.firstOrNull { normalize(it.model).isNotBlank() && normalize(it.model) in name }?.let { return it.id }
        throw StandException("${vehicle.name}: машина не найдена в API аномалий")
    }

    private suspend fun cached(): List<VehicleOut> {
        cache?.takeIf { now() - it.first < CACHE_TTL_MS }?.let { return it.second }
        return mutex.withLock {
            cache?.takeIf { now() - it.first < CACHE_TTL_MS }?.let { return it.second }
            val fresh = try {
                api.vehicles()
            } catch (e: IOException) {
                throw StandException("API аномалий недоступен: проверьте сеть")
            }
            cache = now() to fresh
            fresh
        }
    }

    private fun normalize(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")

    companion object {
        /** Список машин API живёт недолго: после ingest/sync на стенде сопоставление не должно ждать перезапуска. */
        const val CACHE_TTL_MS = 60_000L
    }
}
