package ru.petrovich.telemetry.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import ru.petrovich.telemetry.data.api.ApiFactory
import java.util.UUID

/** Сотрудник, которому Петрович может позвонить или написать: служба безопасности, бухгалтер, механик. */
@Serializable
data class Contact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: String,
    val phone: String = "",
    val email: String = "",
) {
    val initials: String get() = name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
}

private val listSerializer = ListSerializer(Contact.serializer())

fun decodeContacts(raw: String): List<Contact> =
    if (raw.isBlank()) emptyList() else runCatching { ApiFactory.json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())

fun encodeContacts(contacts: List<Contact>): String = ApiFactory.json.encodeToString(listSerializer, contacts)
