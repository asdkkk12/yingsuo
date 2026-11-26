package com.local.attendance

import android.content.Intent
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.attendance.automation.*
import com.local.attendance.alarm.FailureAlarmService
import com.local.attendance.core.*
import com.local.attendance.data.*
import com.local.attendance.testing.FixtureActivity
import com.local.attendance.ui.MainActivity
import com.local.attendance.execution.ExecutionService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.graph
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }
    @Before fun prepareDedicatedEmulator() {
        // connectedAndroidTest can uninstall/reinstall the app, clearing a11y consent.
        // This setup is exclusively for the emulator/dedicated test device.
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        val service="${context.packageName}/com.local.attendance.automation.FeishuAccessibilityService"
        val old=shell("settings get secure enabled_accessibility_services").trim()
        val services=(if(old=="null" || old.isBlank()) emptyList() else old.split(':')) + service
        shell("settings put secure enabled_accessibility_services ${services.distinct().joinToString(":")}")
        shell("settings put secure accessibility_enabled 1")
        runBlocking { withTimeout(12_000) { while(FeishuAccessibilityService.current==null) delay(100) } }
    }
    private fun task(mode: ExecutionMode = ExecutionMode.LIVE): TaskEntity {
        val now=System.currentTimeMillis()
        return TaskEntity("device-test-${UUID.randomUUID()}",SchedulePolicy.date(now).toString(),Shift.OFF.name,now,mode.name)
    }
    private suspend fun advanceToInspect(t: TaskEntity) {
        app.repository.dao.insert(t)
        assertTrue(app.repository.transition(t.key,TaskState.STARTING))
        app.repository.dao.setDeadline(t.key,System.currentTimeMillis(),System.currentTimeMillis()+120_000)
        assertTrue(app.repository.transition(t.key,TaskState.NAVIGATING))
        assertTrue(app.repository.transition(t.key,TaskState.INSPECTING))
    }
    @Test fun atomicReservationOnlyOneWinner() = runBlocking<Unit> {
        val t=task();advanceToInspect(t)
        val results=coroutineScope { (1..12).map { async(Dispatchers.IO) { app.repository.reserveSubmit(t.key) } }.awaitAll() }
        assertEquals(1,results.count { it })
        assertTrue(app.repository.dao.get(t.key)!!.submitReserved)
        assertFalse(app.repository.transition(t.key,TaskState.INSPECTING))
        app.repository.transition(t.key,TaskState.CANCELLED)
    }
    @Test fun navigationModeCannotReserveSubmission() = runBlocking<Unit> {
        val t=task(ExecutionMode.NAVIGATION_ONLY);advanceToInspect(t)
        assertFalse(app.repository.reserveSubmit(t.key))
        app.repository.transition(t.key,TaskState.CANCELLED)
    }
    @Test fun successWinsAgainstLateTimeout() = runBlocking<Unit> {
        val t=task();advanceToInspect(t)
        assertTrue(app.repository.transition(t.key,TaskState.ALREADY_DONE))
        assertFalse(app.repository.transition(t.key,TaskState.FAILED_RINGING))
        assertEquals(TaskState.ALREADY_DONE,app.repository.dao.get(t.key)!!.status)
    }
    @Test fun acknowledgementCannotBeRestoredAsFailure() = runBlocking<Unit> {
        val t=task();app.repository.dao.insert(t)
        assertTrue(app.repository.transition(t.key,TaskState.FAILED_RINGING,"测试"))
        assertTrue(app.repository.transition(t.key,TaskState.FAILED_ACKNOWLEDGED))
        assertFalse(app.repository.transition(t.key,TaskState.FAILED_RINGING))
        assertTrue(app.repository.dao.get(t.key)!!.acknowledgedAt>0)
    }
    @Test fun resumeFutureTaskAfterPauseDoesNotSkipToday() = runBlocking<Unit> {
        val t=task().copy(scheduledAt=System.currentTimeMillis()+60_000)
        app.repository.dao.insert(t)
        app.repository.transition(t.key,TaskState.CANCELLED)
        assertEquals(1,app.repository.dao.replanFuture(t.key,System.currentTimeMillis()))
        assertEquals(TaskState.SCHEDULED,app.repository.dao.get(t.key)!!.status)
        app.repository.transition(t.key,TaskState.CANCELLED)
    }
    @Test fun accessibleFixtureAllowsOnlyExpectedShiftAndLiveMode() {
        FixtureActivity.clicks=0;FixtureActivity.outside=false
        ActivityScenario.launch<FixtureActivity>(Intent(context,FixtureActivity::class.java)).use {
            instrumentation.waitForIdleSync()
            Thread.sleep(700)
            instrumentation.runOnMainSync {
                assertNotNull("Enable the accessibility service before device tests",FeishuAccessibilityService.current)
                val adapter=FeishuPageAdapter(AppSettings(packageName=context.packageName,path=emptyList()))
                val page=adapter.readSnapshot()
                assertTrue(page.summary(),page.valid)
                val date=SchedulePolicy.date(System.currentTimeMillis())
                assertEquals(Decision.SKIP,AttendancePolicy.decide(page,date,Shift.ON,false))
                assertEquals(Decision.SUBMIT,AttendancePolicy.decide(page,date,Shift.OFF,false))
                assertFalse(adapter.submit(date,Shift.ON,ExecutionMode.LIVE))
                assertThrows(IllegalStateException::class.java) { adapter.submit(date,Shift.OFF,ExecutionMode.NAVIGATION_ONLY) }
                assertEquals(0,FixtureActivity.clicks)
                assertTrue(adapter.submit(date,Shift.OFF,ExecutionMode.LIVE))
            }
            instrumentation.waitForIdleSync();Thread.sleep(350)
            instrumentation.runOnMainSync {
                val adapter=FeishuPageAdapter(AppSettings(packageName=context.packageName,path=emptyList()))
                assertTrue(AttendancePolicy.success(adapter.readSnapshot(),SchedulePolicy.date(System.currentTimeMillis()),Shift.OFF))
                assertFalse(adapter.submit(SchedulePolicy.date(System.currentTimeMillis()),Shift.OFF,ExecutionMode.LIVE))
                assertEquals(1,FixtureActivity.clicks)
            }
        }
    }
    @Test fun alarmPersistsInBackgroundUntilExplicitAcknowledgement() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val t=task(ExecutionMode.NAVIGATION_ONLY)
            runBlocking {
                app.repository.dao.insert(t)
                withContext(Dispatchers.Main) { app.alarms.fail(t.key,"模拟器响铃测试") }
                delay(1500)
                assertEquals(TaskState.FAILED_RINGING,app.repository.dao.get(t.key)!!.status)
                withContext(Dispatchers.Main) { assertTrue(FailureAlarmService.current?.isPlaying == true) }
            }
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            Thread.sleep(700)
            runBlocking {
                assertEquals(TaskState.FAILED_RINGING,app.repository.dao.get(t.key)!!.status)
                withContext(Dispatchers.Main) { assertTrue(FailureAlarmService.current?.isPlaying == true) }
                withContext(Dispatchers.Main) { app.alarms.acknowledge(t.key) }
                delay(500)
                app.alarms.restore()
                assertEquals(TaskState.FAILED_ACKNOWLEDGED,app.repository.dao.get(t.key)!!.status)
                withContext(Dispatchers.Main) { assertFalse(FailureAlarmService.current?.isPlaying == true) }
            }
        }
    }
    @Test fun independentSystemWatchdogStartsFailureAlarm() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val t=task(ExecutionMode.NAVIGATION_ONLY)
            runBlocking {
                app.repository.dao.insert(t)
                withContext(Dispatchers.Main) { app.scheduler.armWatchdog(t.key,System.currentTimeMillis()+1800) }
                withTimeout(10_000) {
                    while(app.repository.dao.get(t.key)?.status!=TaskState.FAILED_RINGING) delay(100)
                }
                assertEquals(TaskState.FAILED_RINGING,app.repository.dao.get(t.key)!!.status)
                withContext(Dispatchers.Main) { app.alarms.acknowledge(t.key) }
            }
        }
    }
    @Test fun navigationFailureFromWakeToTimeoutDoesNotSubmit() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val t=task(ExecutionMode.NAVIGATION_ONLY)
            runBlocking {
                app.settings.configure(context.packageName,emptyList(),false)
                app.repository.dao.insert(t)
                withContext(Dispatchers.Main) {
                    app.scheduler.armWatchdog(t.key,System.currentTimeMillis()+120_000)
                    ExecutionService.start(context,t.key)
                }
                withTimeout(55_000) {
                    while(app.repository.dao.get(t.key)?.status!=TaskState.FAILED_RINGING) delay(200)
                }
                assertFalse(app.repository.dao.get(t.key)!!.submitReserved)
                assertEquals("Must actually wake and reach navigation, not fail a readiness check",1,
                    app.repository.dao.stageCount(t.key,TaskState.NAVIGATING.name))
                withContext(Dispatchers.Main) { app.alarms.acknowledge(t.key) }
                app.settings.configure("com.ss.android.lark",listOf("工作台","考勤"),false)
            }
        }
    }
}
