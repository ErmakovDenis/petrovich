package ru.petrovich.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.petrovich.telemetry.ui.common.TimeRange
import ru.petrovich.telemetry.ui.common.validationMessage
import java.time.LocalDateTime

class TimeRangeTest {
    private val from = LocalDateTime.of(2026, 11, 12, 8, 0)
    private val to = LocalDateTime.of(2026, 11, 15, 18, 0)

    @Test
    fun validRangeAndLabel() {
        val r = TimeRange(from, to)
        assertTrue(r.isValid)
        assertNull(r.validationMessage())
        assertEquals("12.11 08:00–15.11 18:00", r.label)
    }

    @Test
    fun sameDayLabelOmitsSecondDate() {
        val r = TimeRange(from, from.withHour(18))
        assertEquals("12.11 08:00–18:00", r.label)
    }

    @Test
    fun rejectsInvertedOrTooLong() {
        assertFalse(TimeRange(to, from).isValid)
        assertEquals("Начало должно быть раньше конца", TimeRange(to, from).validationMessage())
        assertTrue(TimeRange(from, from.plusDays(90)).isValid)
        assertTrue(TimeRange(from, from.plusDays(TimeRange.MAX_DAYS)).isValid)
        val tooLong = TimeRange(from, from.plusDays(TimeRange.MAX_DAYS + 1))
        assertFalse(tooLong.isValid)
        assertTrue(tooLong.validationMessage()!!.contains("180"))
    }

    @Test
    fun previousOfSameLength() {
        val r = TimeRange(from, to)
        val prev = r.previousOfSameLength()
        assertEquals(from, prev.to)
        assertEquals(from.minus(r.span), prev.from)
        assertTrue(prev.isValid)
    }

    @Test
    fun presets() {
        val now = LocalDateTime.of(2026, 11, 17, 12, 0)
        assertEquals(now.toLocalDate().atStartOfDay(), TimeRange.today(now).from)
        assertEquals(now.minusHours(24), TimeRange.lastHours(24, now).from)
        assertEquals(now.minusDays(7), TimeRange.lastDays(7, now).from)
    }
}
