package com.local.attendance.core

import org.junit.Assert.*
import org.junit.Test

class TransitionRulesTest {
    @Test fun cannotSubmitFromNavigationOrFailure() {
        assertFalse(TransitionRules.allows(TaskState.NAVIGATING,TaskState.SUBMITTING))
        assertFalse(TransitionRules.allows(TaskState.FAILED_RINGING,TaskState.SUBMITTING))
    }
    @Test fun terminalSuccessCannotBeOverwrittenByTimeout() {
        for(state in listOf(TaskState.SUCCEEDED,TaskState.ALREADY_DONE,TaskState.FAILED_ACKNOWLEDGED,TaskState.CANCELLED))
            assertFalse(TransitionRules.allows(state,TaskState.FAILED_RINGING))
    }
    @Test fun acknowledgeNeverMeansSuccess() {
        assertTrue(TransitionRules.allows(TaskState.FAILED_RINGING,TaskState.FAILED_ACKNOWLEDGED))
        assertFalse(TransitionRules.allows(TaskState.FAILED_ACKNOWLEDGED,TaskState.SUCCEEDED))
    }
    @Test fun interruptedSubmitCannotReturnToInspection() {
        assertFalse(TransitionRules.allows(TaskState.SUBMITTING,TaskState.INSPECTING))
        assertFalse(TransitionRules.allows(TaskState.VERIFYING,TaskState.SUBMITTING))
    }
}
