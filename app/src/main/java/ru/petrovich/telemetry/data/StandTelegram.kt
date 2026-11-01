package ru.petrovich.telemetry.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.petrovich.telemetry.data.api.ApiFactory

// Telegram-бот (ml/telegram_bot, ml/fleet_service schemas/telegram.py). Подключение двумя путями:
// - код из бота: бот в ответ на /start показывает код XXXX-XXXX, пользователь вводит его здесь (Telegram — где угодно);
// - ссылка: приложение просит у стенда одноразовую ссылку t.me/<бот>?start=<код> и открывает её (Telegram на этом телефоне).
// Бот работает по доступу фоновой проверки этого пользователя на стенде, поэтому подключение возможно, только когда
// «Фоновая проверка на стенде» включена и доступ передан (иначе стенд отвечает 409).
// Что и по каким машинам присылать в чат, настраивается и здесь, и в боте (/settings): подписка хранится на стенде, поэтому
// бот и приложение показывают одно и то же.

/** Тело `POST /v1/telegram/link`: схема и логин — из заголовков запроса. */
@Serializable
data class TelegramLinkRequest(val deviceId: String, val schemaName: String, val utcOffsetMinutes: Int)

/** Ссылка на бота с одноразовым кодом и срок её действия, epoch millis. */
@Serializable
data class TelegramLink(val url: String, val expiresAt: Long)

/** Тело `POST /v1/telegram/pair`: код, который бот показал в чате. */
@Serializable
data class TelegramPairRequest(val deviceId: String, val schemaName: String, val utcOffsetMinutes: Int, val code: String)

/** Списки «Проблемы» и «Парк» в боте. */
@Serializable
data class TelegramListSettings(
    /** true — только машины из подписки; false — все, что видны. Выключенные события не показываются в любом случае. */
    val subscribedOnly: Boolean = false,
    /** INFO — любые, WARNING — от предупреждений, CRITICAL — только критичные. */
    val minSeverity: String = "INFO",
    /** Только события за последние столько часов; null — за весь срок хранения. */
    val periodHours: Int? = null,
    val pageSize: Int = 10,
    /** newest — сначала новые; severity — сначала критичные. */
    val order: String = "newest",
)

/** Что показывать в карточке события в боте. */
@Serializable
data class TelegramCardSettings(
    val description: Boolean = true,
    /** «Петрович помнит»: когда раньше было такое же событие на этой машине. */
    val memory: Boolean = true,
    val parameter: Boolean = false,
    val source: Boolean = false,
    val resolvedBy: Boolean = true,
    /** «Проверено повторно»: пропуски в данных вокруг события и работал ли двигатель. */
    val recheck: Boolean = true,
    val reviewButton: Boolean = true,
    val resolveButtons: Boolean = true,
    /** «Показать данные»: график параметра вокруг события. */
    val dataButton: Boolean = true,
)

/** Утренний доклад и недельная сводка в боте — по местному времени. */
@Serializable
data class TelegramReportSettings(
    val daily: Boolean = true,
    /** «ЧЧ:ММ». */
    val dailyTime: String = "09:30",
    val weekdaysOnly: Boolean = false,
    /** Присылать доклад, даже если за сутки событий не было. */
    val whenEmpty: Boolean = true,
    val weekly: Boolean = true,
    /** 1 — понедельник … 7 — воскресенье. */
    val weeklyDay: Int = 1,
    val weeklyTime: String = "09:30",
)

/**
 * Настройки чата: что и по каким машинам присылать, как показывать списки и карточки. «Критично» по выбранным машинам
 * приходит всегда, кроме выключенных событий ([mutedKinds]).
 */
@Serializable
data class TelegramSubscription(
    /** null — все машины (и новые тоже). */
    val vehicleIds: List<String>? = null,
    val warnings: Boolean = true,
    val info: Boolean = false,
    /** Не больше стольких некритичных уведомлений в сутки; null — без лимита. */
    val dailyLimit: Int? = 3,
    /** Тихие часы по местному времени; null — тихих часов нет. */
    val quietFromHour: Int? = 22,
    val quietToHour: Int? = 7,
    /** Выключенные события ([TELEGRAM_KINDS]): не приходят совсем, даже критичные, и не показываются в списках бота. */
    val mutedKinds: List<String> = emptyList(),
    /** Повтор того же события на машине — не чаще раза в столько часов; null — каждый раз. */
    val repeatHours: Int? = null,
    val lists: TelegramListSettings = TelegramListSettings(),
    val card: TelegramCardSettings = TelegramCardSettings(),
    val reports: TelegramReportSettings = TelegramReportSettings(),
)

/** Группы событий на экране — как MetricCategory стенда. */
val TELEGRAM_CATEGORIES = linkedMapOf("FUEL" to "Топливо", "ENGINE" to "Двигатель", "POWER" to "Питание", "MOTION" to "Движение")

/** События, которые можно выключить, по группам — как KINDS стенда (schemas/telegram.py) и /settings → «События» в боте. */
val TELEGRAM_KINDS: Map<String, List<Pair<String, String>>> = linkedMapOf(
    "FUEL" to listOf("drain" to "Слив топлива", "drop" to "Резкое падение уровня топлива"),
    "ENGINE" to listOf("overheat" to "Перегрев двигателя", "oil" to "Низкое давление масла"),
    "POWER" to listOf("volt" to "Напряжение вне нормы", "power" to "Пропадание питания"),
    "MOTION" to listOf("brake" to "Низкое давление в тормозной системе"),
)

@Serializable
data class TelegramChat(
    val chatId: String,
    val schemaName: String,
    val userName: String,
    val subscription: TelegramSubscription,
    val linkedAt: Long,
    /** @имя или имя в Telegram; null — чат подключён до того, как стенд стал его запоминать. */
    val title: String? = null,
)

@Serializable
data class TelegramVehicle(val id: String, val name: String)

/** Ответ `GET /v1/telegram/chats`: настроен ли бот, чаты пользователя в схеме и его машины. */
@Serializable
data class TelegramChats(
    val configured: Boolean,
    val botUrl: String? = null,
    val chats: List<TelegramChat> = emptyList(),
    val vehicles: List<TelegramVehicle> = emptyList(),
    val vehiclesError: String? = null,
)

class StandTelegram(
    private val stand: StandClient,
    private val utcOffsetMinutes: () -> Int = StandRequests::utcOffsetMinutes,
) {
    suspend fun link(deviceId: String, schemaName: String): TelegramLink {
        val body = ApiFactory.json.encodeToString(
            TelegramLinkRequest.serializer(), TelegramLinkRequest(deviceId, schemaName, utcOffsetMinutes()),
        )
        return StandRequests.decode(TelegramLink.serializer(), stand.post("v1/telegram/link", body))
    }

    suspend fun pair(deviceId: String, schemaName: String, code: String): TelegramChat {
        val body = ApiFactory.json.encodeToString(
            TelegramPairRequest.serializer(), TelegramPairRequest(deviceId, schemaName, utcOffsetMinutes(), code.trim()),
        )
        return StandRequests.decode(TelegramChat.serializer(), stand.post("v1/telegram/pair", body))
    }

    suspend fun chats(): TelegramChats = StandRequests.decode(TelegramChats.serializer(), stand.get("v1/telegram/chats"))

    suspend fun setSubscription(chatId: String, subscription: TelegramSubscription): TelegramChat {
        val body = FULL.encodeToString(TelegramSubscription.serializer(), subscription)
        return StandRequests.decode(TelegramChat.serializer(), stand.post(listOf("v1", "telegram", "chats", chatId, "subscription"), body))
    }

    suspend fun unlink(chatId: String): TelegramChats =
        StandRequests.decode(TelegramChats.serializer(), stand.post(listOf("v1", "telegram", "chats", chatId, "unlink"), "{}"))

    private companion object {
        // Подписка целиком, с null и значениями по умолчанию: пропущенное поле стенд заменил бы своим умолчанием
        // («без лимита» превратилось бы в 3 в день).
        val FULL = Json(ApiFactory.json) { encodeDefaults = true; explicitNulls = true }
    }
}
