package ru.petrovich.telemetry.chat

import kotlinx.serialization.Serializable
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandRequests
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.Contact

// Письма из чата (ml/fleet_service, schemas/emails.py): ассистент готовит черновик, отправляет его стенд только
// по нажатию «Отправить» здесь. Адресов модель не видит; пользователю они показываются в черновике.

@Serializable
data class EmailRecipient(val id: String, val name: String, val role: String, val email: String)

/**
 * Черновик письма из ответа ассистента на стенде; отправляет стенд по «Отправить». Не путать с [EmailDraft] локального
 * «Петровича» — тот открывается в почтовом клиенте устройства.
 */
@Serializable
data class StandEmailDraft(
    val id: String,
    val recipients: List<EmailRecipient>,
    val subject: String,
    val body: String,
    /** До какого момента черновик можно отправить, epoch millis. */
    val expiresAt: Long,
    @kotlinx.serialization.Transient val personal: Boolean = false,
    @kotlinx.serialization.Transient val status: String = "draft",
    @kotlinx.serialization.Transient val personalScope: String? = null,
)

/** Ответ `POST /v1/emails/{id}/confirm` и `/cancel`. */
@Serializable
data class EmailResult(val id: String, val status: String, val sentAt: Long? = null)

/** Действия пользователя с черновиком; в тестах — подставные. */
interface EmailActions {
    /** Отправить: письмо уходит только отсюда. Отказ стенда (срок истёк, лимит, ошибка SMTP) — исключение с текстом. */
    suspend fun send(draftId: String): EmailResult

    suspend fun cancel(draftId: String): EmailResult
    suspend fun send(draft: StandEmailDraft): EmailResult =
        if (draft.personal) throw StandException("Персональные письма недоступны") else send(draft.id)
    suspend fun cancel(draft: StandEmailDraft): EmailResult =
        if (draft.personal) throw StandException("Персональные письма недоступны") else cancel(draft.id)
}

/**
 * Стенд отвечает 409 «Письмо уже отправлено» на повторное подтверждение: например, письмо ушло, а ответ на первое
 * нажатие потерялся по дороге. Для пользователя это «отправлено», а не ошибка.
 */
fun StandException.meansAlreadySent(): Boolean = code == 409 && message.orEmpty().startsWith("Письмо уже отправлено")

/** Список перечитывается при каждом запросе; телефон не передаётся. */
@Serializable
data class EmailContact(val id: String, val name: String, val role: String, val email: String)

fun List<Contact>.emailContacts(): List<EmailContact> = filter { it.email.isNotBlank() }.map {
    EmailContact(it.id, it.name.trim(), it.role.trim(), it.email.trim())
}

@Serializable
private data class EmailConfirmation(val emailContacts: List<EmailContact>? = null)

class StandEmails(
    private val stand: StandClient,
    private val contacts: suspend () -> List<Contact>? = { null },
    private val personalAction: (suspend (String, String, String, String) -> EmailResult)? = null,
) : EmailActions {
    override suspend fun send(draft: StandEmailDraft): EmailResult {
        if (!draft.personal) return send(draft.id)
        val owner = draft.personalScope ?: throw StandException("Не подтверждён владелец письма")
        val body = ApiFactory.json.encodeToString(EmailConfirmation.serializer(), EmailConfirmation(contacts()?.emailContacts()))
        return personalAction?.invoke(owner, draft.id, "confirm", body) ?: throw StandException("Персональные письма недоступны")
    }
    override suspend fun cancel(draft: StandEmailDraft): EmailResult {
        if (!draft.personal) return cancel(draft.id)
        val owner = draft.personalScope ?: throw StandException("Не подтверждён владелец письма")
        return personalAction?.invoke(owner, draft.id, "cancel", "{}") ?: throw StandException("Персональные письма недоступны")
    }

    override suspend fun send(draftId: String): EmailResult {
        val body = ApiFactory.json.encodeToString(
            EmailConfirmation.serializer(), EmailConfirmation(contacts()?.emailContacts()),
        )
        return StandRequests.decode(
            EmailResult.serializer(), stand.post(listOf("v1", "emails", draftId, "confirm"), body),
        )
    }

    override suspend fun cancel(draftId: String): EmailResult =
        StandRequests.decode(EmailResult.serializer(), stand.post(listOf("v1", "emails", draftId, "cancel"), "{}"))
}

fun StandEmailDraft.uiState(): DraftState = when (status) {
    "sent" -> DraftState.SENT
    "cancelled" -> DraftState.CANCELLED
    "expired" -> DraftState.EXPIRED
    "sending" -> DraftState.SENDING
    "failed" -> DraftState.FAILED
    else -> DraftState.PENDING
}
