package ru.petrovich.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.petrovich.telemetry.data.settings.AppSettings
import java.time.LocalTime

/** Тихие часы приложения — то же правило, что у Telegram-бота: окно из настроек, через полночь. */
class QuietHoursTest {
    private val night = AppSettings(quietHoursEnabled = true, quietHoursStart = "22:00", quietHoursEnd = "07:00")

    @Test
    fun appPushDependsOnTelegramLink() {
        assertTrue(AppSettings(appPushEnabled = false, telegramLinked = false).shouldSendAppPush())
        assertFalse(AppSettings(appPushEnabled = false, telegramLinked = true).shouldSendAppPush())
        assertTrue(AppSettings(appPushEnabled = true, telegramLinked = true).shouldSendAppPush())
    }

    @Test
    fun overnightWindowMatchesTelegram() {
        assertTrue(night.isQuietAt(LocalTime.of(22, 0)))
        assertTrue(night.isQuietAt(LocalTime.of(23, 30)))
        assertTrue(night.isQuietAt(LocalTime.of(0, 0)))
        assertTrue(night.isQuietAt(LocalTime.of(6, 59)))
        assertFalse(night.isQuietAt(LocalTime.of(7, 0)))
        assertFalse(night.isQuietAt(LocalTime.of(21, 59)))
        assertFalse(night.isQuietAt(LocalTime.of(12, 0)))
    }

    @Test
    fun usesTheHoursFromSettings() {
        val custom = AppSettings(quietHoursStart = "21:30", quietHoursEnd = "08:15")
        assertTrue(custom.isQuietAt(LocalTime.of(21, 30)))
        assertFalse(custom.isQuietAt(LocalTime.of(21, 29)))
        assertTrue(custom.isQuietAt(LocalTime.of(8, 14)))
        assertFalse(custom.isQuietAt(LocalTime.of(8, 15)))
    }

    @Test
    fun sameDayWindow() {
        val day = AppSettings(quietHoursStart = "13:00", quietHoursEnd = "15:00")
        assertFalse(day.isQuietAt(LocalTime.of(12, 59)))
        assertTrue(day.isQuietAt(LocalTime.of(13, 0)))
        assertTrue(day.isQuietAt(LocalTime.of(14, 59)))
        assertFalse(day.isQuietAt(LocalTime.of(15, 0)))
    }

    @Test
    fun disabledOrEmptyWindowIsNeverQuiet() {
        assertFalse(night.copy(quietHoursEnabled = false).isQuietAt(LocalTime.of(23, 0)))
        assertFalse(night.copy(quietHoursStart = "08:00", quietHoursEnd = "08:00").isQuietAt(LocalTime.of(8, 0)))
        assertFalse(night.copy(quietHoursStart = "не время").isQuietAt(LocalTime.of(23, 0)))
    }

    @Test
    fun dailyReportOnlyInLateWindowAndNotInQuiet() {
        val s = night.copy(reportTime = "08:00")
        assertFalse(s.isDailyReportDueAt(LocalTime.of(7, 59)))
        assertTrue(s.isDailyReportDueAt(LocalTime.of(8, 0)))
        assertTrue(s.isDailyReportDueAt(LocalTime.of(10, 59)))
        assertFalse(s.isDailyReportDueAt(LocalTime.of(11, 0)))
        // Раньше слали любой тик после 08:00 — отсюда «всё хорошо» в 22:40.
        assertFalse(s.isDailyReportDueAt(LocalTime.of(22, 40)))
    }

    @Test
    fun dailyReportSkippedWhenReportTimeFallsInQuietHours() {
        val s = night.copy(reportTime = "22:00")
        assertFalse(s.isDailyReportDueAt(LocalTime.of(22, 15)))
        assertFalse(s.copy(quietHoursEnabled = false).isDailyReportDueAt(LocalTime.of(1, 0))) // уже > 3ч
        assertTrue(s.copy(quietHoursEnabled = false).isDailyReportDueAt(LocalTime.of(23, 0)))
    }
}
