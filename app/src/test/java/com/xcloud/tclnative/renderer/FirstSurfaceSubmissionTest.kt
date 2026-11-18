package com.xcloud.tclnative.renderer

import org.junit.Assert.*
import org.junit.Test

class FirstSurfaceSubmissionTest {
    @Test fun aPostedFrameFromTheDestroyedSurfaceCannotStartPlayback() {
        val gate = FirstSurfaceSubmission()
        gate.surfaceChanged()
        val old = checkNotNull(gate.begin())
        gate.surfaceChanged()
        gate.surfaceChanged()
        assertFalse(gate.confirm(old, valid = true))

        val current = checkNotNull(gate.begin())
        assertNotEquals(old, current)
        assertTrue(gate.confirm(current, valid = true))
        assertNull(gate.begin())
    }

    @Test fun anInvalidSurfaceCanBeFollowedByAValidSubmission() {
        val gate = FirstSurfaceSubmission()
        val first = checkNotNull(gate.begin())
        assertNull(gate.begin())
        assertFalse(gate.confirm(first, valid = false))
        assertTrue(gate.confirm(checkNotNull(gate.begin()), valid = true))
    }
}
