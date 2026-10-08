"""Пороги правил аномалий. Значения по умолчанию равны зашитым в `BaselineAnomalyDetector.kt` приложения
(подобраны по реальным данным 16.09.2026). Меняются переменными `FS_RULES__<ИМЯ>` (см. .env.example)."""

from pydantic import BaseModel, Field


class RuleThresholds(BaseModel):
    # Топливо: резкое падение уровня — не меньше fuel_drop_min_liters за fuel_drop_window_minutes;
    # после срабатывания fuel_drop_cooldown_minutes новых не создаём.
    fuel_drop_window_minutes: int = Field(15, ge=1)
    fuel_drop_min_liters: float = Field(25, gt=0)
    fuel_drop_cooldown_minutes: int = Field(60, ge=0)

    # Пропадание питания: с этой длительности — WARNING, короче — INFO.
    power_long_minutes: int = Field(2, ge=0)

    # Напряжение: ниже voltage_min_value — «нет данных» (CAN при выключенном зажигании шлёт 0).
    voltage_min_value: float = 5
    # Медиана выше — бортсеть 24 В, иначе 12 В.
    voltage_24v_median: float = 18
    voltage_24v_low: float = 24
    voltage_24v_critical: float = 22
    voltage_24v_high: float = 30.5
    voltage_12v_low: float = 12
    voltage_12v_critical: float = 11
    voltage_12v_high: float = 15
    # CRITICAL, если выше high на столько вольт.
    voltage_critical_over_high: float = 1.5
    # Двигатель считается работающим с этих оборотов; эпизод — не короче voltage_min_minutes.
    voltage_min_rpm: float = 500
    voltage_min_minutes: int = Field(5, ge=0)

    # Перегрев: выше overheat_celsius — WARNING, выше overheat_critical_celsius — CRITICAL; не короче overheat_min_minutes.
    overheat_celsius: float = 100
    overheat_critical_celsius: float = 105
    overheat_min_minutes: int = Field(2, ge=0)

    # Давление масла ниже oil_low_kpa (0 — нет данных) на оборотах выше oil_min_rpm, не короче oil_min_minutes.
    oil_low_kpa: float = 100
    oil_min_rpm: float = 800
    oil_min_minutes: int = Field(1, ge=0)

    # Тормозные контуры ниже brake_low_kpa на оборотах выше brake_min_rpm, не короче brake_min_minutes.
    brake_low_kpa: float = 550
    brake_min_rpm: float = 600
    brake_min_minutes: int = Field(10, ge=0)
