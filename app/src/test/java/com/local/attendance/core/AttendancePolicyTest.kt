package com.local.attendance.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AttendancePolicyTest {
    private val date = LocalDate.of(2026, 11, 26)
    private fun page(range: RangeStatus = RangeStatus.INSIDE, sections: List<Section> = listOf(Section(Shift.OFF, null, 7, true))) =
        PageSnapshot(date, sections, range, true)
    @Test fun correctShiftCanSubmit() { assertEquals(Decision.SUBMIT, AttendancePolicy.decide(page(), date, Shift.OFF, false)) }
    @Test fun navigationCannotBeConfiguredToClickPunchButtons() {
        assertFalse(NavigationPolicy.safeLabel("上班打卡"));assertFalse(NavigationPolicy.safeLabel("下班打卡"))
        assertFalse(NavigationPolicy.safeLabel("确认"));assertTrue(NavigationPolicy.safeLabel("工作台"));assertTrue(NavigationPolicy.safeLabel("考勤"))
    }
    @Test fun wrongDateNeverSubmits() { assertEquals(Decision.FAIL, AttendancePolicy.decide(page(), date.plusDays(1), Shift.OFF, false)) }
    @Test fun unknownRangeNeverSubmits() { assertEquals(Decision.FAIL, AttendancePolicy.decide(page(RangeStatus.UNKNOWN), date, Shift.OFF, false)) }
    @Test fun outsideWaitsExactlyOneCycle() {
        assertEquals(Decision.WAIT_RANGE, AttendancePolicy.decide(page(RangeStatus.OUTSIDE), date, Shift.OFF, false))
        assertEquals(Decision.FAIL, AttendancePolicy.decide(page(RangeStatus.OUTSIDE), date, Shift.OFF, true))
    }
    @Test fun existingRecordSkipsEvenIfOutside() {
        assertEquals(Decision.SKIP, AttendancePolicy.decide(page(RangeStatus.OUTSIDE, listOf(Section(Shift.OFF, "21:46", null, false))), date, Shift.OFF, false))
    }
    @Test fun eveningMustNotClickNewMorningButton() {
        val p = page(sections = listOf(Section(Shift.ON, "10:58", null, false), Section(Shift.OFF, "21:46", null, false), Section(Shift.ON, null, 9, true)))
        assertEquals(Decision.SKIP, AttendancePolicy.decide(p, date, Shift.OFF, false))
        assertEquals(Decision.FAIL, AttendancePolicy.decide(p, date, Shift.ON, false))
    }
    @Test fun noOffShiftCannotUseMorningButton() { assertEquals(Decision.FAIL, AttendancePolicy.decide(page(sections=listOf(Section(Shift.ON,null,9,true))), date, Shift.OFF,false)) }
    @Test fun disabledButtonFails() { assertEquals(Decision.FAIL, AttendancePolicy.decide(page(sections=listOf(Section(Shift.OFF,null,9,false))), date, Shift.OFF,false)) }
    @Test fun successRequiresRecordForExpectedShiftAndDate() {
        val p = page(sections=listOf(Section(Shift.ON,"09:00",null,false)))
        assertFalse(AttendancePolicy.success(p,date,Shift.OFF))
        assertFalse(AttendancePolicy.success(p,date.plusDays(1),Shift.ON))
        assertTrue(AttendancePolicy.success(p,date,Shift.ON))
    }
}
