"""Имена параметров AutoGRAPH (как в data/AutoGraphParameters.kt)."""

FUEL_LEVEL = "TankMainFuelLevel"
FUEL_DRAIN_VOLUME = "TankMainFuelDnVol"
FUEL_UP_VOLUME = "TankMainFuelUpVol"
POWER = "Power"
IGNITION = "DIgnition"
IGNITION_CAN = "DIgnitionCAN"
RPM = "Rotation"
COOLANT_TEMP = "TemperatureCOOL"
OIL_PRESSURE = "PressureOIL"
SPEED = "Speed"
# Так в схеме AutoGRAPH (sic). Есть только у Урал NEXT, у FAW нет.
BATTERY_VOLTAGE = "BattaryVOLTAGE"

# Параметры, которые приходят по CAN: при выключенном зажигании они равны 0,
# а обороты «замирают» на последнем значении — это не данные, а их отсутствие.
CAN_PARAMETERS = frozenset({
    RPM, COOLANT_TEMP, OIL_PRESSURE,
    "CANFinstant", "ConsumptionCAN", "EngineLOAD", "TemperatureBOOST", "TemperatureOIL", "GazLOAD", "SpeedCAN",
})
