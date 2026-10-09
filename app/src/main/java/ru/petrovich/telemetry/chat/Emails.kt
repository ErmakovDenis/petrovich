package ru.petrovich.telemetry.chat

import kotlinx.serialization.Serializable
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandRequests

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
)

/** Ответ `POST /v1/emails/{id}/confirm` и `/cancel`. */
@Serializable
data class EmailResult(val id: String, val status: String, val sentAt: Long? = null)

/** Действия пользователя с черновиком; в тестах — подставные. */
interface EmailActions {
    /** Отправить: письмо уходит только отсюда. Отказ стенда (срок истёк, лимит, ошибка SMTP) — исключение с текстом. */
    suspend fun send(draftId: String): EmailResult

    suspend fun cancel(draftId: String): EmailResult
}

/**
 * Стенд отвечает 409 «Письмо уже отправлено» на повторное подтверждение: например, письмо ушло, а ответ на первое
 * нажатие потерялся по дороге. Для пользователя это «отправлено», а не ошибка.
 */
fun StandException.meansAlreadySent(): Boolean = code == 409 && message.orEmpty().startsWith("Письмо уже отправлено")

class StandEmails(private val stand: StandClient) : EmailActions {
    override suspend fun send(draftId: String): EmailResult =
        StandRequests.decode(EmailResult.serializer(), stand.post(listOf("v1", "emails", draftId, "confirm"), "{}"))

    override suspend fun cancel(draftId: String): EmailResult =
        StandRequests.decode(EmailResult.serializer(), stand.post(listOf("v1", "emails", draftId, "cancel"), "{}"))
}
