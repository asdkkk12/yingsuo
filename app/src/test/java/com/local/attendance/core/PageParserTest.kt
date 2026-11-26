package com.local.attendance.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PageParserTest {
    private fun n(id: Int, text: String, y: Int, clickable: Boolean = false, enabled: Boolean = true) =
        UiElement(id,text,y,y+30,enabled,if(clickable) id else null)
    private fun base() = listOf(n(0,"2026.11.26",10),n(1,"上班",100),n(2,"已打卡 10:58",150),n(3,"下班",300),n(4,"下班打卡",400,true),n(5,"已进入打卡范围：办公室",450))
    @Test fun parsesDateAndGroupsRecords() {
        val p=PageParser.parse(base())
        assertTrue(p.valid);assertEquals(LocalDate.of(2026,11,26),p.date)
        assertEquals("10:58",p.target(Shift.ON)?.recordTime)
        assertNull(p.target(Shift.OFF)?.recordTime)
        assertEquals(4,p.target(Shift.OFF)?.buttonId)
        assertEquals(RangeStatus.INSIDE,p.range)
    }
    @Test fun ignoresButtonWithoutClickableAncestor() {
        val p=PageParser.parse(base().map { if(it.id==4) it.copy(actionId=null) else it })
        assertNull(p.target(Shift.OFF)?.buttonId)
    }
    @Test fun missingDateFailsClosed() { assertFalse(PageParser.parse(base().drop(1)).valid) }
    @Test fun conflictingDatesFailClosed() { assertFalse(PageParser.parse(base()+n(10,"2026.11.25",20)).valid) }
    @Test fun twoTargetButtonsFailClosed() { assertFalse(PageParser.parse(base()+n(10,"下班打卡",430,true)).valid) }
    @Test fun wrongShiftButtonFailsClosed() { assertFalse(PageParser.parse(base().map { if(it.id==4) it.copy(text="上班打卡") else it }).valid) }
    @Test fun recordAndButtonInSameSectionAreAmbiguous() { assertFalse(PageParser.parse(base()+n(10,"已打卡 22:00",350)).valid) }
    @Test fun invalidRecordTimeFailsClosed() { assertFalse(PageParser.parse(base().map { if(it.id==2) it.copy(text="已打卡 25:99") else it }).valid) }
    @Test fun conflictingRangeIsUnknown() { assertEquals(RangeStatus.UNKNOWN,PageParser.parse(base()+n(10,"不在打卡范围",470)).range) }
    @Test fun splitRecordCanBeReadWhenUniqueOnSameLine() {
        val p=PageParser.parse(base().filter { it.id!=2 }+n(10,"已打卡",150)+n(11,"10:58",150))
        assertTrue(p.valid);assertEquals("10:58",p.target(Shift.ON)?.recordTime)
    }
}
