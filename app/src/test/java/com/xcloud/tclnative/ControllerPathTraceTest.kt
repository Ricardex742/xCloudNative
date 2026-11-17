package com.xcloud.tclnative

import com.xcloud.tclnative.settings.ControllerProfile
import com.xcloud.tclnative.webrtc.*
import com.xcloud.tclnative.signaling.SessionStartupTrace
import org.junit.Assert.*
import org.junit.Test

class ControllerPathTraceTest {
    @Test fun profilesAndRouteChangesAreObservableWithoutKeepingDescriptorsOrUsbBusPaths() {
        val trace=ControllerPathTrace()
        val usb=ControllerSource.Usb("/private/bus/path@4",0x1234,0x5678,0,"private device name")
        val raw=GamepadState(leftY=-.75f,rightY=.5f)
        val profile=ControllerProfile(invertLeftY=true)
        trace.record(0,usb,raw,profile.apply(raw),profile)
        trace.record(0,usb,GamepadState(),GamepadState(),profile)
        val report=trace.diagnostics()
        assertTrue(report.contains("USB 1234:5678/i0; changes=0; invertY=true/false"))
        assertTrue(report.contains("-0.750/0.750;0.500/0.500"))
        assertFalse(report.contains("private"))
        trace.record(0,ControllerSource.Android(7),raw,raw,null)
        assertTrue(trace.diagnostics().contains("Android id=7; changes=1; invertY=false/false"))
        trace.clear();assertEquals("",trace.diagnostics())
    }
    @Test fun changedAndroidAliasesAreRetiredAtomicallyWithoutASecondLogicalPlayerOrAStaleProfile() {
        val router=ControllerRouter();router.beginSession(true)
        val usb=ControllerSource.Usb("usb@4",1,2,0,"Demo")
        assertEquals(0,router.slot(7));assertEquals(1,router.slot(8));assertEquals(2,router.slot(90))
        router.update(0,GamepadState(leftY=-1f));router.update(1,GamepadState(leftY=1f))
        router.update(2,GamepadState(buttons=16))
        assertEquals(0,router.attachUsb(usb,8,setOf(7,8)))
        assertEquals(usb,router.sourceFor(0));assertNull(router.sourceFor(1))
        assertEquals(GamepadState(),router.physical(0));assertEquals(GamepadState(),router.physical(1))
        assertEquals(ControllerSource.Android(90),router.sourceFor(2));assertEquals(16,router.physical(2).buttons)
        assertNull(router.remove(7));assertNull(router.remove(8));assertEquals(usb,router.sourceFor(0))
        assertNull(router.existingSlot(ControllerSource.Android(1234)))
        assertNull(router.sourceFor(1))
    }
    @Test fun startupObservationsDistinguishAConfirmedServerQueueFromProvisioningAndClearBetweenAttempts() {
        var now=0L;val trace=SessionStartupTrace { now }
        assertTrue(trace.diagnostics().contains("NOT_STARTED"))
        trace.observe("WaitingForResources");now=180_000;trace.observe("WaitingForResources")
        trace.estimate(com.xcloud.tclnative.signaling.QueueStatus(0,now,
            com.xcloud.tclnative.signaling.QueueEstimate(0),now))
        assertTrue(trace.diagnostics().contains("QUEUE_ESTIMATE/seconds=0; age=0ms; failed=false"))
        assertTrue(trace.diagnostics().contains("replies=2; age=0ms; queue=180000ms"))
        now=181_000;trace.observe("ReadyToConnect");now=182_000;trace.observe("Provisioned")
        assertTrue(trace.diagnostics().contains("Provisioned; replies=4; age=0ms; queue=181000ms"))
        trace.observe("private service payload")
        assertTrue(trace.diagnostics().contains("OTHER"));assertFalse(trace.diagnostics().contains("private"))
        trace.reset();assertTrue(trace.diagnostics().contains("NOT_STARTED; replies=0; age=n/d; queue=0ms"))
        assertTrue(trace.diagnostics().contains("QUEUE_ESTIMATE/seconds=n/d; age=n/d; failed=false"))
    }
}
