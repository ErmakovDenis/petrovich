package ru.petrovich.telemetry.anomaly

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandRequests
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil

// Фоновая проверка на стенде (ml/fleet_service, schemas/background.py): доступ стенда к AutoGRAPH от имени
// пользователя и отметки показанных уведомлений.

/** Тело `POST /v1/background/access`: токен — из заголовка, пароль — только с согласия и только если его нет на стенде. */
@Serializable
data class AccessRequest(val deviceId: String, val utcOffsetMinutes: Int, val savePassword: Boolean, val password: String? = null)

@Serializable
data class DeviceRequest(val deviceId: String)

/** Состояние доступа устройства на стенде. */
@Serializable
data class AccessStatus(
    /** Фоновая проверка на стенде включена и настроена. */
    val enabled: Boolean,
    /** Стенд хранит доступ этого устройства. */
    val registered: Boolean,
    val tokenActive: Boolean = false,
    val tokenSince: Long? = null,
    val passwordStored: Boolean = false,
    /** Когда доступ последний раз сработал в фоновой проверке, epoch millis; последняя ошибка — по-русски. */
    val lastOkAt: Long? = null,
    val lastError: String? = null,
    /** Последняя успешная проверка схемы, epoch millis. */
    val lastScanAt: Long? = null,
    val intervalMinutes: Double = 15.0,
    val windowHours: Int = 3,
)

@Serializable
data class ClaimRequest(val deviceId: String, val ids: List<String>)

@Serializable
data class ClaimResponse(val ids: List<String>)

/** Запросы фоновой проверки к стенду от имени пользователя ([StandClient]: токен сессии, схема, логин). */
class StandBackground(
    private val stand: StandClient,
    private val utcOffsetMinutes: () -> Int = StandRequests::utcOffsetMinutes,
) {
    /** Выдать или обновить доступ: стенд получает текущий токен (из заголовка) и пояс; пароль — по согласию. */
    suspend fun grant(deviceId: String, savePassword: Boolean, password: String? = null): AccessStatus {
        val body = ApiFactory.json.encodeToString(
            AccessRequest.serializer(), AccessRequest(deviceId, utcOffsetMinutes(), savePassword, password),
        )
        return StandRequests.decode(AccessStatus.serializer(), stand.post("v1/background/access", body))
    }

    suspend fun status(deviceId: String): AccessStatus =
        StandRequests.decode(AccessStatus.serializer(), stand.get("v1/background/access", mapOf("deviceId" to deviceId)))

    /** Отозвать доступ: стенд удаляет токен и пароль устройства и больше не проверяет от его имени. */
    suspend fun revoke(deviceId: String): AccessStatus {
        val body = ApiFactory.json.encodeToString(DeviceRequest.serializer(), DeviceRequest(deviceId))
        return StandRequests.decode(AccessStatus.serializer(), stand.post("v1/background/access/revoke", body))
    }

    /** Какие из [ids] пользователь ещё не получал уведомлением ни на одном устройстве (стенд отмечает их показанными). */
    suspend fun claim(deviceId: String, ids: List<String>): List<String> {
        val body = ApiFactory.json.encodeToString(ClaimRequest.serializer(), ClaimRequest(deviceId, ids))
        return StandRequests.decode(ClaimResponse.serializer(), stand.post("v1/notifications/claim", body)).ids
    }
}

/** Последняя синхронизация доступа — для экрана настроек. */
data class BackgroundState(val status: AccessStatus? = null, val error: String? = null)

/**
 * Доступ стенда для фоновой проверки по настройкам ([AppSettings.backgroundOnServer]). Решение владельца проекта:
 * по умолчанию стенд работает на токене сессии, который приложение обновляет при каждом фоновом обращении; пароль
 * уходит на стенд, только если пользователь разрешил стенду входить самостоятельно
 * ([AppSettings.standPasswordConsent]), и только когда его там ещё нет.
 *
 * Выключено (переключатель, хранилище или «Аномалии со стенда», «Фоновая проверка», демо-режим) — доступ, выданный
 * раньше, отзывается: стенд удаляет токен и пароль устройства.
 */
class BackgroundAccess(
    private val settings: suspend () -> AppSettings,
    private val update: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val stand: StandBackground,
    private val newDeviceId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    /** Почему не удалось передать пароль при последней синхронизации (доступ по токену при этом выдан). */
    private var passwordError: String? = null
    private val _state = MutableStateFlow(BackgroundState())
    val state: StateFlow<BackgroundState> = _state.asStateFlow()

    /**
     * Привести доступ на стенде к настройкам: включено — выдать или обновить (свежий токен), выключено — отозвать
     * выданный раньше. Нет связи со стендом — исключение; отзыв повторится при следующем вызове.
     */
    suspend fun sync(): AccessStatus? = mutex.withLock {
        val s = settings()
        passwordError = null
        val result = runCatchingCancellable {
            if (!s.backgroundOnServer) {
                if (s.standAccessGranted) revokeLocked(s)
                null
            } else {
                grantLocked(s)
            }
        }
        _state.value = BackgroundState(result.getOrNull(), result.exceptionOrNull()?.message ?: passwordError)
        result.getOrThrow()
    }

    /** Отозвать доступ сейчас (например, перед сменой адреса стенда — потом отозвать будет негде). */
    suspend fun revoke() = mutex.withLock {
        val s = settings()
        if (s.standAccessGranted) revokeLocked(s)
        _state.value = BackgroundState()
    }

    private suspend fun revokeLocked(s: AppSettings) {
        stand.revoke(deviceId(s))
        update { it.copy(standAccessGranted = false) }
    }

    private suspend fun grantLocked(s: AppSettings): AccessStatus {
        val id = deviceId(s)
        var status = stand.grant(id, s.standPasswordConsent)
        // Токен на стенде уже есть: отзывать придётся, даже если отправка пароля ниже не удастся.
        val interval = ceil(status.intervalMinutes).toInt().coerceAtLeast(1)
        update { it.copy(standAccessGranted = true, standScanIntervalMinutes = interval) }
        // Пароль — только если стенд его ещё не хранит (первое согласие, смена учётной записи, пароль не подошёл),
        // и не тот, что стенд уже отверг: повторы входа с неверным паролем могут заблокировать учётную запись.
        val hash = sha256(s.password)
        if (s.standPasswordConsent && !status.passwordStored && s.password.isNotEmpty() && hash != s.standPasswordRejected) {
            // Не вышло — доступ по токену всё равно действует: ошибка только в состоянии, без исключения.
            try {
                status = stand.grant(id, true, s.password)
            } catch (e: StandException) {
                if (e.code == 422 || e.code == 403) update { it.copy(standPasswordRejected = hash) }
                passwordError = e.message
            }
        }
        return status
    }

    private suspend fun deviceId(s: AppSettings): String {
        if (s.deviceId.isNotBlank()) return s.deviceId
        val id = newDeviceId()
        update { it.copy(deviceId = id) }
        return id
    }

    /**
     * Из аномалий, о которых устройство собирается уведомить, — те, о которых пользователь ещё не получал уведомления
     * на других своих устройствах. Стенд не ответил — все (лучше повтор на втором устройстве, чем пропуск).
     */
    suspend fun claim(anomalies: List<Anomaly>): List<Anomaly> {
        if (anomalies.isEmpty()) return anomalies
        val s = settings()
        if (!s.backgroundOnServer) return anomalies
        val id = mutex.withLock { deviceId(settings()) }
        val ids = runCatchingCancellable { stand.claim(id, anomalies.map { it.id }) }.getOrNull()?.toSet()
            ?: return anomalies
        return anomalies.filter { it.id in ids }
    }
}

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
