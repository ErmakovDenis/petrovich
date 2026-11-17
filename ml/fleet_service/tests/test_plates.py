from fleet_service.telemetry.plates import from_device, from_name, normalize


def test_from_name_russian_plates():
    assert from_name("КАМАЗ 65115 А101МР66") == "А101МР66"
    assert from_name("Scania R450 К417АУ96") == "К417АУ96"
    assert from_name("Урал NEXT А001АА") == "А001АА"
    assert from_name("Урал NEXT (гн 8901)") == "8901"
    assert from_name("FAW №1") is None
    assert from_name("Камаз (водовоз)") is None


def test_from_properties_preferred():
    props = [{"Name": "Госномер", "Value": "В214ОК66"}]
    assert from_device("КАМАЗ без номера", props) == "В214ОК66"


def test_from_displayname_property():
    props = [{"Name": "DisplayName", "Value": "Урал р807аа11"}]
    assert from_device("Урал", props) == "Р807АА11"
    assert from_name("о556вх11") == "О556ВХ11"
    assert from_name("6049кх11") == "6049КХ11"


def test_from_vehicle_reg_number():
    props = [{"Name": "VehicleRegNumber", "Value": "К 336 УР 196"}]
    assert from_device("LADA Largus  ( новый аг)", props) == "К336УР196"


def test_vehicle_reg_number_skips_serial_falls_back_to_alias():
    props = [
        {"Name": "Alias", "Value": "MAN в530ер186"},
        {"Name": "VehicleRegNumber", "Value": "2041254"},
    ]
    assert from_device("УТМ-17", props) == "В530ЕР186"


def test_normalize_lookalikes():
    assert normalize("a 001 aa 77") == "А001АА77"
