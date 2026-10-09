package ru.petrovich.telemetry.ui.common

import ru.petrovich.telemetry.data.settings.Contact

/** Контакт службы безопасности — для звонка по срочному событию. Нет такого — нет и кнопки звонка. */
fun securityContact(contacts: List<Contact>): Contact? =
    contacts.firstOrNull { it.role.contains("сб", ignoreCase = true) || it.role.contains("безопасн", ignoreCase = true) }

/** Контакт для письма: служба безопасности, а если её нет в списке — первый доступный сотрудник. */
fun mailContact(contacts: List<Contact>): Contact? = securityContact(contacts) ?: contacts.firstOrNull()
