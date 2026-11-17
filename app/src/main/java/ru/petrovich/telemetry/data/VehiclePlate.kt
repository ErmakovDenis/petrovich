package ru.petrovich.telemetry.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ru.petrovich.telemetry.data.api.RProperty

/**
 * Гос. номер из свойств AutoGRAPH или из [Vehicle.name] («Урал NEXT А001АА66»).
 * Буквы номера — только те, что есть и в кириллице, и на российских номерах.
 */
object VehiclePlate {
    private const val LETTERS = "АВЕКМНОРСТУХABEKMHOPCTYX"
    // Регион (2–3 цифры) необязателен: в тестовых/API данных бывает «А001АА» без региона.
    private val IN_NAME = Regex(
        """(?<![А-Яа-яA-Za-z0-9])([$LETTERS])\s*(\d{3})\s*([$LETTERS]{2})(?:\s*(\d{2,3}))?(?![А-Яа-яA-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    /** Парк AutoGRAPH: «Урал NEXT (гн 8901)» — короткий гос. номер в скобках. */
    private val GN_IN_NAME = Regex(
        """(?i)(?:^|[\s(])гн\s*[:.\-]?\s*(\d{3,6})(?=\s*[)]|$|[\s,;])""",
    )
    /** Спецтехника: «6049кх11», «Трэкол 1713кк11» — цифры в начале. */
    private val SPECIAL_IN_NAME = Regex(
        """(?<![А-Яа-яA-Za-z0-9])(\d{3,4})\s*([$LETTERS]{2})\s*(\d{2,3})(?![А-Яа-яA-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val LOOKALIKE = mapOf(
        'A' to 'А', 'B' to 'В', 'E' to 'Е', 'K' to 'К', 'M' to 'М', 'H' to 'Н',
        'O' to 'О', 'P' to 'Р', 'C' to 'С', 'T' to 'Т', 'Y' to 'У', 'X' to 'Х',
    )

    fun from(name: String?, properties: List<RProperty> = emptyList()): String? =
        fromProperties(properties) ?: name?.let(::fromName)

    fun fromName(name: String): String? {
        val formal = IN_NAME.find(name)
        if (formal != null) {
            val region = formal.groupValues.getOrNull(4).orEmpty()
            return normalize(formal.groupValues[1] + formal.groupValues[2] + formal.groupValues[3] + region)
        }
        val special = SPECIAL_IN_NAME.find(name)
        if (special != null) {
            return normalize(special.groupValues[1] + special.groupValues[2] + special.groupValues[3])
        }
        return GN_IN_NAME.find(name)?.groupValues?.get(1)
    }

    fun fromProperties(properties: List<RProperty>): String? {
        // 1) Явные поля «Госномер» / VehicleRegNumber
        for (p in properties) {
            val key = p.name ?: continue
            if (!isPlateProperty(key)) continue
            val raw = p.value.asPlainString()?.trim().orEmpty()
            if (raw.isBlank()) continue
            fromName(raw)?.let { return it }
            // Серийник прибора в VehicleRegNumber («2041254») номером не считаем.
            fallbackPlate(raw)?.let { return it }
        }
        // 2) В этом парке номер часто в DisplayName / Alias: «Урал р807аа11», «MAN в530ер186»
        for (p in properties) {
            val key = p.name?.lowercase()?.replace(Regex("[\\s._\\-]"), "") ?: continue
            if (key !in DISPLAY_KEYS) continue
            val raw = p.value.asPlainString()?.trim().orEmpty()
            if (raw.isBlank()) continue
            fromName(raw)?.let { return it }
        }
        return null
    }

    fun isPlateProperty(name: String): Boolean {
        val n = name.lowercase().replace(Regex("[\\s._\\-]"), "")
        if (n in PLATE_KEYS) return true
        return n.contains("гос") && n.contains("ном")
    }

    /** Короткие цифровые «гн»; длинные цифры — серийник AutoGRAPH, не гос. номер. */
    private fun fallbackPlate(value: String): String? {
        val compact = value.replace(" ", "")
        if (compact.all { it.isDigit() }) {
            return compact.takeIf { it.length in 3..6 }
        }
        return normalize(value)
    }

    /** Кириллица, без пробелов — чтобы «а 001 аа 66» и «A001AA66» совпадали. */
    fun normalize(plate: String): String =
        plate.trim().uppercase().map { LOOKALIKE[it] ?: it }.joinToString("").replace(" ", "")

    private fun JsonElement?.asPlainString(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        else -> null
    }

    private val PLATE_KEYS = setOf(
        "госномер", "госномера", "госзнак", "госзнака", "госрегзнак", "гн",
        "регномер", "регистрационныйномер", "regnumber", "regnum",
        "vehicleregnumber", "vehicleregnum",
        "platenumber", "plateno", "numberplate", "licenseplate", "registrationnumber",
    )
    private val DISPLAY_KEYS = setOf("displayname", "alias", "алиас")
}
