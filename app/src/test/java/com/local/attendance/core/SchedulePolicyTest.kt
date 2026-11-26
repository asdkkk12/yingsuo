package com.local.attendance.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class SchedulePolicyTest {
    private val date=LocalDate.of(2026,11,26)
    @Test fun computesBeijingNotHostTimezone() { assertEquals(Instant.parse("2026-11-26T01:00:00Z").toEpochMilli(),SchedulePolicy.at(date,Shift.ON)) }
    @Test fun firstEnableAfterMorningOnlySchedulesFutureMorning() {
        assertEquals(SchedulePolicy.at(date.plusDays(1),Shift.ON),SchedulePolicy.next(SchedulePolicy.at(date,Shift.ON)+1,Shift.ON))
    }
    @Test fun eveningTodaySurvivesMorningPassed() {
        assertEquals(SchedulePolicy.at(date,Shift.OFF),SchedulePolicy.next(SchedulePolicy.at(date,Shift.ON)+1,Shift.OFF))
    }
    @Test fun catchupBoundaryIsFiveMinutes() {
        val at=SchedulePolicy.at(date,Shift.ON)
        assertTrue(SchedulePolicy.catchUp(at+300_000,at)); assertFalse(SchedulePolicy.catchUp(at+300_001,at));assertFalse(SchedulePolicy.catchUp(at-1,at))
    }
    @Test fun keysSeparateDayAndShift() {
        assertNotEquals(SchedulePolicy.key(date,Shift.ON),SchedulePolicy.key(date,Shift.OFF))
        assertNotEquals(SchedulePolicy.key(date,Shift.ON),SchedulePolicy.key(date.plusDays(1),Shift.ON))
    }
}
