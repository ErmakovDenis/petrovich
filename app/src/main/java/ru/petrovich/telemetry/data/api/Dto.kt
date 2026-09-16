package ru.petrovich.telemetry.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RSchema(
    @SerialName("ID") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Group") val group: String? = null,
)

@Serializable
data class REnumDevices(
    @SerialName("ID") val id: String? = null,
    @SerialName("Groups") val groups: List<RGroupItem> = emptyList(),
    @SerialName("Items") val items: List<RDeviceItem> = emptyList(),
)

@Serializable
data class RGroupItem(
    @SerialName("ID") val id: String,
    @SerialName("ParentID") val parentId: String? = null,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class RDeviceItem(
    @SerialName("ID") val id: String,
    @SerialName("ParentID") val parentId: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Serial") val serial: Int? = null,
    @SerialName("Allowed") val allowed: Boolean = true,
)

@Serializable
data class RParameters(
    @SerialName("ID") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("FinalParams") val finalParams: List<RParameter> = emptyList(),
    @SerialName("OnlineParams") val onlineParams: List<RParameter> = emptyList(),
    @SerialName("TripsParams") val tripsParams: List<RParameter> = emptyList(),
)

@Serializable
data class RParameter(
    @SerialName("Name") val name: String,
    @SerialName("Caption") val caption: String? = null,
    @SerialName("Alias") val alias: String? = null,
    @SerialName("GroupName") val groupName: String? = null,
    @SerialName("Unit") val unit: String? = null,
    @SerialName("Format") val format: String? = null,
    /** 0 — вкл/выкл, 1/2 — целые/флаги, 4 — число, 5 — дата, 6 — интервал, 12 — местоположение. */
    @SerialName("ReturnType") val returnType: Int? = null,
)

@Serializable
data class RPoint(
    @SerialName("Lat") val lat: Double? = null,
    @SerialName("Lng") val lng: Double? = null,
)

@Serializable
data class ROnlineInfo(
    @SerialName("ID") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("DT") val dt: String? = null,
    @SerialName("Speed") val speed: Double? = null,
    @SerialName("Address") val address: String? = null,
    @SerialName("LastPosition") val lastPosition: RPoint? = null,
    @SerialName("Final") val final: JsonElement? = null,
)
