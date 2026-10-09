package ru.petrovich.telemetry.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.data.settings.SettingsRepository
import ru.petrovich.telemetry.ui.common.advice
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.common.plural
import ru.petrovich.telemetry.ui.common.previousOccurrence
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.home.AnomalyOrder
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

enum class Author { USER, AGENT }

/** Письмо-черновик, собранное по запросу — отправляется через почтовый клиент устройства. */
data class EmailDraft(
    val recipientRole: String,
    val recipientName: String?,
    val recipientEmail: String?,
    val subject: String,
    val body: String,
)

/** Предложение передать случай сотруднику (например, механику) без звонка. */
data class AssignSuggestion(val anomalyId: String, val assigneeName: String, val assigneeRole: String)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val author: Author,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    /** Если задано — под сообщением показывается кнопка действия (см. [actionLabel]). */
    val openAnomalyId: String? = null,
    /** Подпись кнопки действия под ответом, например «Разобраться с X452»; null — «Открыть карточку». */
    val actionLabel: String? = null,
    val emailDraft: EmailDraft? = null,
    val assignable: AssignSuggestion? = null,
)

data class ChatReply(
    val text: String,
    val openAnomalyId: String? = null,
    val actionLabel: String? = null,
    val emailDraft: EmailDraft? = null,
    val assignable: AssignSuggestion? = null,
)

/**
 * ИИ-агент, отвечающий о состоянии автопарка.
 * [contextAnomalyId] — если диалог открыт из карточки конкретной аномалии (или из «Разбора» по ней).
 */
interface ChatAgent {
    /** false — работает без внешней модели (по правилам и данным парка), а не выдуманным ИИ. */
    val connected: Boolean get() = true

    suspend fun reply(history: List<ChatMessage>, contextAnomalyId: String? = null): ChatReply
}

/** Ассистент на стенде: получает саму аномалию, из карточки которой открыт чат, и отвечает текстом. */
interface ContextualChatAgent {
    val connected: Boolean get() = true

    suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?): String
}

/**
 * Выбирает агента на каждый вызов по настройкам: ассистент на стенде ([AppSettings.assistantOnServer])
 * или локальный [local], как раньше. [connectedState] — для заголовка чата, следует за настройками.
 */
class SwitchingChatAgent(
    /** [SettingsRepository.settings]. */
    private val settings: Flow<AppSettings>,
    private val local: ChatAgent,
    private val remote: ContextualChatAgent,
    scope: CoroutineScope,
    /** Аномалия по id из контекста чата — стенду уходит она сама, а не id. */
    private val findAnomaly: (String) -> Anomaly? = { null },
) : ChatAgent {
    val connectedState: StateFlow<Boolean> = settings
        .map { if (it.assistantOnServer) remote.connected else local.connected }
        .stateIn(scope, SharingStarted.Eagerly, local.connected)

    override val connected: Boolean get() = connectedState.value

    override suspend fun reply(history: List<ChatMessage>, contextAnomalyId: String?): ChatReply =
        if (settings.first().assistantOnServer) {
            ChatReply(remote.reply(history, contextAnomalyId?.let(findAnomaly)))
        } else {
            local.reply(history, contextAnomalyId)
        }
}

/** Заглушка на случай, если локальный движок ниже нужно временно отключить. */
class StubChatAgent : ChatAgent {
    override val connected = false
    override suspend fun reply(history: List<ChatMessage>, contextAnomalyId: String?): ChatReply {
        delay(700)
        return ChatReply("ИИ-агент пока не подключён. Здесь появится ответ о состоянии машин и выявленных аномалиях.")
    }
}

/**
 * Локальный «Петрович»: без внешней LLM, отвечает по правилам и реальным данным парка —
 * сохранённым аномалиям, списку машин и контактам из настроек. Не выдаёт себя за настоящий ИИ:
 * [connected] = false, в чате это подписано явно.
 */
class PetrovichAgent : ChatAgent {
    override val connected = false

    override suspend fun reply(history: List<ChatMessage>, contextAnomalyId: String?): ChatReply {
        delay(350)
        val question = history.lastOrNull { it.author == Author.USER }?.text.orEmpty()
        val q = question.lowercase(Locale("ru"))
        val anomalies = ServiceLocator.anomalyStore.anomalies.value
        val context = contextAnomalyId?.let { id -> anomalies.firstOrNull { it.id == id } }

        context?.let { ctx ->
            recallReply(q, ctx, anomalies)?.let { return it }
            if (PROOF.containsMatchIn(q)) return ChatReply(ctx.advice())
            if (WHAT_HAPPENED.containsMatchIn(q)) return ChatReply(ctx.description)
            if (ASSIGN.containsMatchIn(q)) return assignReply(ctx)
        }

        if (EMAIL_VERB.containsMatchIn(q) && EMAIL_SUBJECT.containsMatchIn(q)) {
            return emailReply(q, context, anomalies)
        }

        vehicleMentioned(q)?.let { vehicle ->
            // Честный ответ: если датчик топлива молчит, не выдумываем расход — так и говорим (3.11).
            val silentSensor = anomalies.firstOrNull {
                it.vehicleId == vehicle.id && it.kind == "nodata" && it.resolution != Resolution.FALSE_ALARM
            }
            if (silentSensor != null && FUEL_QUESTION.containsMatchIn(q)) {
                val since = silentSensor.time?.toLocalDate()?.title() ?: silentSensor.eventTime
                val mechanicNote = if (silentSensor.assignedTo != null) " Механик уже в курсе." else ""
                return ChatReply(
                    "Не могу сказать: датчик на ${vehicle.name} молчит с $since.$mechanicNote",
                    openAnomalyId = silentSensor.id,
                    actionLabel = "Разобраться с ${vehicle.name}",
                )
            }
            val target = anomalies.filter { it.vehicleId == vehicle.id && it.isNew }.sortedWith(AnomalyOrder).firstOrNull()
            return if (target != null) {
                ChatReply(
                    "${vehicle.name}: ${target.title.lowercase(Locale("ru"))}. Открываю карточку.",
                    openAnomalyId = target.id,
                    actionLabel = "Разобраться с ${vehicle.name}",
                )
            } else {
                ChatReply("У «${vehicle.name}» сейчас нет неразобранных случаев.")
            }
        }

        if (STATUS.containsMatchIn(q)) {
            val pending = anomalies.filter { it.isNew }.sortedWith(AnomalyOrder)
            if (pending.isEmpty()) return ChatReply("Всё в порядке, замечаний нет.")
            val top = pending.take(2)
            val summary = top.joinToString(", ") { "у ${it.vehicleName} ${it.description.replaceFirstChar(Char::lowercaseChar)}" }
            val urgent = pending.any { it.severity == ru.petrovich.telemetry.anomaly.Severity.CRITICAL }
            val headline = "${pending.size} ${plural(pending.size, "проблема", "проблемы", "проблем")}"
            val worst = top.first()
            return ChatReply(
                (if (urgent) "Срочно. " else "") + "$headline: $summary.",
                openAnomalyId = worst.id,
                actionLabel = "Разобраться с ${worst.vehicleName}",
            )
        }
        if (WEEK.containsMatchIn(q)) {
            val week = weekAnomalies(anomalies)
            val byCat = MetricCategory.entries.map { c -> c to week.count { it.category == c } }.filter { it.second > 0 }
            return ChatReply(
                "За 7 дней ${week.size} ${plural(week.size, "аномалия", "аномалии", "аномалий")}" +
                    if (byCat.isEmpty()) "." else ": " + byCat.joinToString("; ") { (c, n) -> "${c.title.lowercase(Locale("ru"))} — $n" } + ".",
            )
        }
        if (TOP.containsMatchIn(q)) {
            val top = weekAnomalies(anomalies).groupingBy { it.vehicleName }.eachCount().entries.sortedByDescending { it.value }.take(3)
            return ChatReply(if (top.isEmpty()) "За неделю особых нарушителей нет." else "Больше всего аномалий: " + top.joinToString(", ") { "${it.key} (${it.value})" })
        }
        if (CONNECTIVITY.containsMatchIn(q)) {
            // "power" — пропадание питания, самый частый косвенный признак обрыва связи с трекером.
            val affected = anomalies.filter { it.isNew && it.kind == "power" }.map { it.vehicleName }.distinct()
            return ChatReply(if (affected.isEmpty()) "Проблем со связью не вижу." else "Возможны проблемы со связью или питанием: ${affected.joinToString(", ")}.")
        }

        return ChatReply("Не уверен, что понял вопрос. Спросите про конкретную машину, про аномалии за неделю или кто больше всех жжёт топливо.")
    }

    private fun recallReply(q: String, ctx: Anomaly, anomalies: List<Anomaly>): ChatReply? {
        if (!RECALL.containsMatchIn(q)) return null
        val prev = anomalies.previousOccurrence(ctx)
        return ChatReply(
            if (prev != null) {
                val when_ = prev.time?.toLocalDate()?.title() ?: prev.eventTime
                "Да, $when_ на этой же машине уже было похожее: «${prev.title.lowercase(Locale("ru"))}»."
            } else {
                "За всё время, что я слежу за парком, похожих случаев на ${ctx.vehicleName} не было. Это первый раз."
            },
        )
    }

    private suspend fun assignReply(ctx: Anomaly): ChatReply {
        val mechanic = ServiceLocator.settings.current().contacts.firstOrNull { it.role.contains("механ", ignoreCase = true) }
        return if (mechanic != null) ChatReply("Готов передать «${mechanic.name}» (${mechanic.role}).", assignable = AssignSuggestion(ctx.id, mechanic.name, mechanic.role))
        else ChatReply("В настройках нет контакта с ролью «механик». Добавьте сотрудника на вкладке «Настройки» — и я смогу передавать ему случаи.")
    }

    private suspend fun emailReply(q: String, context: Anomaly?, anomalies: List<Anomaly>): ChatReply {
        val contacts = ServiceLocator.settings.current().contacts
        val toAccountant = ACCOUNTANT.containsMatchIn(q) && !SECURITY.containsMatchIn(q)
        if (toAccountant) {
            val month = YearMonth.now()
            val monthLabel = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru")))
            val monthAnomalies = anomalies.filter {
                it.category == MetricCategory.FUEL && it.resolution != Resolution.FALSE_ALARM &&
                    it.time?.let { t -> YearMonth.from(t) == month } == true
            }
            val contact = contacts.firstOrNull { it.role.contains("бухг", ignoreCase = true) }
            val body = if (monthAnomalies.isEmpty()) "За $monthLabel отклонений по топливу не найдено."
            else "За $monthLabel по топливу: ${monthAnomalies.size} ${plural(monthAnomalies.size, "случай", "случая", "случаев")}. " +
                monthAnomalies.joinToString("; ") { "${it.vehicleName} — ${it.title.lowercase(Locale("ru"))}" }
            return ChatReply(
                "Собрал отчёт по топливу за $monthLabel.",
                emailDraft = EmailDraft("Бухгалтер", contact?.name, contact?.email, "Отчёт по топливу · $monthLabel", body),
            )
        }
        val target = context ?: anomalies.filter { it.isNew }.sortedWith(AnomalyOrder).firstOrNull()
            ?: return ChatReply("Сейчас нет открытых случаев, чтобы о них написать.")
        val contact = contacts.firstOrNull { it.role.contains("сб", ignoreCase = true) || it.role.contains("безопасн", ignoreCase = true) }
        val whenText = target.time?.let { "${it.toLocalDate().title()} в ${it.hhmm()}" } ?: target.eventTime
        val body = "$whenText у ${target.vehicleName} — ${target.description}"
        return ChatReply(
            "Собрал письмо про ${target.vehicleName}.",
            emailDraft = EmailDraft("Начальник СБ", contact?.name, contact?.email, "${target.vehicleName}: ${target.title}", body),
        )
    }

    private suspend fun vehicleMentioned(q: String): Vehicle? {
        if (q.length < 3) return null
        val vehicles = runCatchingCancellable { ServiceLocator.telemetry.vehicles() }.getOrDefault(emptyList())
        return vehicles.firstOrNull { v -> v.name.split(" ", "-").any { token -> token.length >= 3 && q.contains(token.lowercase(Locale("ru"))) } }
    }

    private fun weekAnomalies(anomalies: List<Anomaly>, today: LocalDate = LocalDate.now()): List<Anomaly> =
        anomalies.filter { a ->
            a.resolution != Resolution.FALSE_ALARM &&
                a.time?.toLocalDate()?.let { !it.isBefore(today.minusDays(6)) && !it.isAfter(today) } == true
        }

    private companion object {
        val RECALL = Regex("раньше|повтор|уже было|так делал")
        val PROOF = Regex("доказ")
        val WHAT_HAPPENED = Regex("что случилось|почему|что произошло")
        val ASSIGN = Regex("передай|назначь|механик")
        val EMAIL_VERB = Regex("напиши|отправь|составь")
        val EMAIL_SUBJECT = Regex("отчёт|письм|сб\\b|безопасн|бухгалтер")
        val ACCOUNTANT = Regex("бухгалтер|отчёт")
        val SECURITY = Regex("сб\\b|безопасн")
        val STATUS = Regex("в порядке|всё ли|как дела|не так")
        val WEEK = Regex("недел|сколько аномал")
        val TOP = Regex("кто больше|топ|жгут|жжёт|расход")
        val CONNECTIVITY = Regex("без связи|не на связи|связь")
        val FUEL_QUESTION = Regex("сколько.*(сожг|расход|топлив|жж[её])")
    }
}
