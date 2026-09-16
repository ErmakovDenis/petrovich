package ru.petrovich.telemetry.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import ru.petrovich.telemetry.data.api.AutoGraphApi
import ru.petrovich.telemetry.data.settings.SettingsRepository
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AuthException(message: String) : Exception(message)

/** Реальные данные из AutoGRAPH API. */
class AutoGraphTelemetryRepository(
    private val api: AutoGraphApi,
    private val settings: SettingsRepository,
) : TelemetryRepository {

    private val mutex = Mutex()
    private var token: String? = null
    private var tokenFor: Pair<String, String>? = null
    @Volatile private var vehiclesCache: Pair<String, List<Vehicle>>? = null

    /** Длина одного запроса GetTripTables: сутки сырых данных по машине — ~6 МБ JSON (≈0.8 МБ в gzip). */
    private val chunk: Duration = Duration.ofHours(6)

    suspend fun login(userName: String, password: String): String {
        val offsetMinutes = ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds / 60
        val raw = try {
            api.login(userName, password, offsetMinutes).string()
        } catch (e: HttpException) {
            if (e.code() == 401) throw AuthException("Неверный логин или пароль")
            throw e
        }
        val t = raw.trim().trim('"')
        if (t.isEmpty()) throw AuthException("Неверный логин или пароль")
        return t
    }

    suspend fun schemas(userName: String, password: String): List<Schema> {
        val t = login(userName, password)
        return api.enumSchemas(t).map { Schema(it.id, it.name ?: it.id) }
    }

    private suspend fun session(): String = mutex.withLock {
        val s = settings.current()
        if (s.userName.isBlank()) throw AuthException("Укажите логин и пароль в настройках")
        val creds = s.userName to s.password
        if (token == null || tokenFor != creds) {
            token = login(s.userName, s.password)
            tokenFor = creds
            vehiclesCache = null
            parametersCache.clear()
        }
        token!!
    }

    private suspend fun schemaId(session: String): String {
        val s = settings.current()
        if (s.schemaId.isNotBlank()) return s.schemaId
        val first = api.enumSchemas(session).firstOrNull()
            ?: throw IllegalStateException("Нет доступных схем")
        settings.update { it.copy(schemaId = first.id, schemaName = first.name ?: first.id) }
        return first.id
    }

    /** Повторяет запрос один раз после переавторизации, если сессия истекла. */
    private suspend fun <T> withSession(block: suspend (session: String, schemaId: String) -> T): T {
        return try {
            val s = session()
            block(s, schemaId(s))
        } catch (e: HttpException) {
            if (e.code() != 401) throw e
            mutex.withLock { token = null }
            val s = session()
            block(s, schemaId(s))
        }
    }

    override suspend fun vehicles(): List<Vehicle> = withSession { session, schemaId ->
        vehiclesCache?.takeIf { it.first == schemaId }?.second?.let { return@withSession it }
        val resp = api.enumDevices(session, schemaId)
        val groups = resp.groups.associateBy { it.id }
        val list = resp.items
            .filter { it.allowed }
            .map { Vehicle(it.id, it.name ?: "ТС ${it.serial ?: it.id.take(8)}", it.parentId?.let { p -> groups[p]?.name }) }
            .sortedBy { it.name }
        vehiclesCache = schemaId to list
        list
    }

    private data class SelectedParameters(val list: List<ParameterInfo>, val aggregation: Map<String, Aggregation>)

    // Заполняется из нескольких корутин одновременно (AnomalyScanner); повторный запрос параметров безвреден.
    private val parametersCache = ConcurrentHashMap<String, SelectedParameters>()

    private suspend fun parameters(session: String, schemaId: String, vehicleId: String): SelectedParameters {
        parametersCache[vehicleId]?.let { return it }
        val available = api.enumParameters(session, schemaId, vehicleId)[vehicleId]?.onlineParams.orEmpty()
        val (list, aggregation) = AutoGraphParameters.select(available)
        return SelectedParameters(list, aggregation).also { parametersCache[vehicleId] = it }
    }

    override suspend fun telemetry(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): VehicleTelemetry =
        withSession { session, schemaId ->
            val params = parameters(session, schemaId, vehicle.id)
            if (params.list.isEmpty()) {
                return@withSession VehicleTelemetry(vehicle, from, to, emptyMap())
            }
            val names = params.list.joinToString(",") { it.name }
            val ranges = generateSequence(from) { it.plus(chunk) }
                .takeWhile { it.isBefore(to) }
                .map { start -> start to minOf(start.plus(chunk), to) }
                .toList()
            val builder = TripTablesMapper.Builder(vehicle, from, to, params.list, params.aggregation)
            // Части периода грузятся последовательно: память на разбор нужна только под один поток.
            for ((sd, ed) in ranges) {
                val body = api.getTripTables(
                    session = session,
                    schemaId = schemaId,
                    ids = vehicle.id,
                    from = sd.format(API_DATE),
                    to = ed.format(API_DATE),
                    onlineParams = names,
                )
                withContext(Dispatchers.IO) { body.use { builder.read(it.charStream()) } }
            }
            withContext(Dispatchers.Default) { builder.build() }
        }

    companion object {
        private val API_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}
