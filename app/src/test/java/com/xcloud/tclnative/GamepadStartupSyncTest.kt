package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GamepadStartupSyncTest {
    private fun body(message: ChannelMessage): JSONObject {
        assertEquals("control",message.channel);assertFalse(message.binary)
        return JSONObject(String(message.bytes,Charsets.UTF_8))
    }
    @Test fun aFreshTransportResetsThenRegistersSlotZeroOnceWithoutAnyGameInput() {
        var now=1000L;val sync=GamepadStartupSync { now }
        val reset=sync.begin()
        assertEquals("gamepadChanged",body(reset).getString("message"))
        assertEquals(0,body(reset).getInt("gamepadIndex"));assertFalse(body(reset).getBoolean("wasAdded"))
        assertNull(sync.remainingMs());assertTrue(sync.due().isEmpty())
        sync.sent(reset);assertEquals(500L,sync.remainingMs())
        now+=499;assertTrue(sync.due().isEmpty());assertFalse(sync.registered)
        now++;val add=sync.due().single()
        assertEquals(3,body(add).length());assertTrue(body(add).getBoolean("wasAdded"))
        assertEquals(0,body(add).getInt("gamepadIndex"))
        assertFalse(sync.registered);assertTrue(sync.due().isEmpty())
        sync.sent(add);assertTrue(sync.registered)
        assertTrue(sync.diagnostics().contains("ADDED; removed=true; added=true; added_after=500ms"))
        sync.close();assertTrue(sync.due().isEmpty())
    }
    @Test fun closingAnOldAttemptCancelsItsRegistrationWhileTheNextAttemptHasItsOwnDeadline() {
        var now=0L;val old=GamepadStartupSync { now }
        old.sent(old.begin());now=200;old.close()
        val next=GamepadStartupSync { now };next.sent(next.begin())
        now=500;assertTrue(old.due().isEmpty());assertTrue(next.due().isEmpty())
        assertTrue(old.diagnostics().contains("CANCELLED"));assertFalse(old.registered)
        now=700;val added=next.due().single();next.sent(added)
        assertTrue(next.registered);assertFalse(old.registered)
    }
    @Test fun anUnacceptedAdditionIsNotReportedAsARegisteredControlAndIsNotRetriedIndefinitely() {
        var now=0L;val sync=GamepadStartupSync { now }
        sync.sent(sync.begin());now=500
        assertEquals(1,sync.due().size)
        now=5000;assertTrue(sync.due().isEmpty());assertFalse(sync.registered)
        assertTrue(sync.diagnostics().contains("QUEUED"));sync.close()
        assertTrue(sync.diagnostics().contains("CANCELLED"))
    }
    @Test fun nativeKeyboardMouseKeepsItsImmediateRegistrationWithoutAScheduledCycle() {
        val sync=GamepadStartupSync(false)
        val message=sync.begin();assertTrue(body(message).getBoolean("wasAdded"))
        sync.sent(message);assertTrue(sync.registered)
        assertNull(sync.remainingMs());assertTrue(sync.due().isEmpty())
        assertTrue(sync.diagnostics().contains("removed=false; added=true"))
    }
}
