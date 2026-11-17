package ru.petrovich.telemetry

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.VehiclePlate
import ru.petrovich.telemetry.data.api.RProperty

class VehiclePlateTest {
    @Test
    fun extractsRussianPlateFromName() {
        assertEquals("А101МР66", VehiclePlate.fromName("КАМАЗ 65115 А101МР66"))
        assertEquals("К417АУ96", VehiclePlate.fromName("Scania R450 К417АУ96"))
        assertEquals("А001АА66", VehiclePlate.fromName("Урал NEXT А001АА66"))
        assertEquals("А001АА", VehiclePlate.fromName("Урал NEXT А001АА"))
        assertEquals("Н630ВЕ196", VehiclePlate.fromName("КАМАЗ 5490 Н630ВЕ196"))
        assertEquals("8901", VehiclePlate.fromName("Урал NEXT (гн 8901)"))
        assertEquals("8902", VehiclePlate.fromName("Урал NEXT (гн 8902)"))
        assertNull(VehiclePlate.fromName("FAW №1"))
        assertNull(VehiclePlate.fromName("Камаз (водовоз)"))
    }

    @Test
    fun prefersPropertyOverName() {
        val props = listOf(RProperty("Госномер", JsonPrimitive("В214ОК66")))
        assertEquals("В214ОК66", VehiclePlate.from(name = "КАМАЗ без номера", properties = props))
    }

    @Test
    fun extractsFromDisplayNameProperty() {
        val props = listOf(RProperty("DisplayName", JsonPrimitive("Урал р807аа11")))
        assertEquals("Р807АА11", VehiclePlate.from(name = "Урал", properties = props))
        assertEquals("О556ВХ11", VehiclePlate.fromName("о556вх11"))
        assertEquals("6049КХ11", VehiclePlate.fromName("6049кх11"))
    }

    @Test
    fun extractsFromVehicleRegNumber() {
        val props = listOf(RProperty("VehicleRegNumber", JsonPrimitive("К 336 УР 196")))
        assertEquals("К336УР196", VehiclePlate.from(name = "LADA Largus  ( новый аг)", properties = props))
    }

    @Test
    fun vehicleRegNumberSerialFallsBackToAlias() {
        val props = listOf(
            RProperty("Alias", JsonPrimitive("MAN в530ер186")),
            RProperty("VehicleRegNumber", JsonPrimitive("2041254")),
        )
        assertEquals("В530ЕР186", VehiclePlate.from(name = "УТМ-17", properties = props))
    }

    @Test
    fun normalizesLatinLookalikes() {
        assertEquals("А001АА77", VehiclePlate.normalize("a 001 aa 77"))
    }

    @Test
    fun vehicleLabels() {
        val withPlateInName = Vehicle("1", "КАМАЗ 65115 А101МР66", plate = "А101МР66")
        assertEquals("А101МР66", withPlateInName.shortLabel)
        assertEquals("КАМАЗ 65115 А101МР66", withPlateInName.label)
        assertEquals("КАМАЗ 65115", withPlateInName.nameWithoutPlate)

        val gn = Vehicle("3", "Урал NEXT (гн 8901)", plate = "8901")
        assertEquals("8901", gn.shortLabel)
        assertEquals("Урал NEXT", gn.nameWithoutPlate)

        val separate = Vehicle("2", "Урал NEXT", plate = "А001АА66")
        assertEquals("А001АА66", separate.shortLabel)
        assertEquals("Урал NEXT А001АА66", separate.label)
    }
}
