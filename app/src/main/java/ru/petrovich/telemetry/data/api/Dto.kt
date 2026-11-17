package ru.petrovich.telemetry.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RSchema(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class REnumDevices(
    @SerialName("Groups") val groups: List<RGroupItem> = emptyList(),
    @SerialName("Items") val items: List<RDeviceItem> = emptyList(),
)

@Serializable
data class RGroupItem(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class RDeviceItem(
    @SerialName("ID") val id: String,
    @SerialName("ParentID") val parentId: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Serial") val serial: Int? = null,
    @SerialName("Allowed") val allowed: Boolean = true,
    /** В т.ч. «Госномер» / RegNumber — откуда берём [ru.petrovich.telemetry.data.Vehicle.plate]. */
    @SerialName("Properties") val properties: List<RProperty> = emptyList(),
)

@Serializable
data class RProperty(
    @SerialName("Name") val name: String? = null,
    @SerialName("Value") val value: JsonElement? = null,
)

@Serializable
data class RParameters(
    // FinalParams/TripsParams не нужны — пропускаются при разборе (ignoreUnknownKeys).
    @SerialName("OnlineParams") val onlineParams: List<RParameter> = emptyList(),
)

@Serializable
data class RParameter(
    @SerialName("Name") val name: String,
    @SerialName("Caption") val caption: String? = null,
    @SerialName("Alias") val alias: String? = null,
    @SerialName("GroupName") val groupName: String? = null,
    @SerialName("Unit") val unit: String? = null,
    /** 0 — вкл/выкл, 1/2 — целые/флаги, 4 — число, 5 — дата, 6 — интервал, 12 — местоположение. */
    @SerialName("ReturnType") val returnType: Int? = null,
)
