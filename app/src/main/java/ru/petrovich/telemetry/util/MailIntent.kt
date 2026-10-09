package ru.petrovich.telemetry.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Открывает почтовый клиент устройства с готовым письмом — отправляет сам пользователь. */
object MailIntent {
    fun send(context: Context, to: String?, subject: String, body: String, attachment: Uri? = null): Boolean {
        val intent = Intent(if (attachment != null) Intent.ACTION_SEND else Intent.ACTION_SENDTO).apply {
            if (attachment != null) {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_STREAM, attachment)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                data = Uri.parse("mailto:")
            }
            if (!to.isNullOrBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, "Отправить письмо").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Открывает телефонный номер в системном наборе — звонок начинает сам пользователь. */
    fun dial(context: Context, phone: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
