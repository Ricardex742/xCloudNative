package com.xcloud.tclnative

import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.hardware.input.InputManager
import android.util.SparseArray
import android.view.InputDevice
import android.os.Looper
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowInputDevice
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,31,34])
@LooperMode(LooperMode.Mode.PAUSED)
class UsbDirectControllerTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun device(name: String="/dev/bus/usb/001/001",klass: Int=255,protocol: Int=1): UsbDevice {
        val input=ReflectionHelpers.newInstance(UsbEndpoint::class.java).apply {
            ReflectionHelpers.setField(this,"mAddress",0x81);ReflectionHelpers.setField(this,"mAttributes",3)
            ReflectionHelpers.setField(this,"mMaxPacketSize",32);ReflectionHelpers.setField(this,"mInterval",4)
        }
        val output=ReflectionHelpers.newInstance(UsbEndpoint::class.java).apply {
            ReflectionHelpers.setField(this,"mAddress",2);ReflectionHelpers.setField(this,"mAttributes",3)
            ReflectionHelpers.setField(this,"mMaxPacketSize",32);ReflectionHelpers.setField(this,"mInterval",4)
        }
        val intf=ReflectionHelpers.newInstance(UsbInterface::class.java).apply {
            ReflectionHelpers.setField(this,"mId",0);ReflectionHelpers.setField(this,"mAlternateSetting",0)
            ReflectionHelpers.setField(this,"mClass",klass);ReflectionHelpers.setField(this,"mSubclass",0x5d)
            ReflectionHelpers.setField(this,"mProtocol",protocol)
            ReflectionHelpers.setField(this,"mEndpoints",arrayOf(input,output))
        }
        return ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this,"mName",name);ReflectionHelpers.setField(this,"mVendorId",0x2dc8)
            ReflectionHelpers.setField(this,"mProductId",0x100);ReflectionHelpers.setField(this,"mProductName","Demo XInput")
            ReflectionHelpers.setField(this,"mInterfaces",arrayOf(intf))
        }
    }
    private fun await(check: () -> Boolean) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!check() && System.nanoTime()<until) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertTrue("USB lifecycle event did not arrive",check())
    }
    private class Io(neutral: Boolean=true): UsbGamepadSession.Io {
        val incoming=LinkedBlockingQueue<UsbGamepadSession.Completion>()
        val packets=CopyOnWriteArrayList<ByteArray>();val claims=CopyOnWriteArrayList<Boolean>()
        @Volatile var closed=false
        init { if(neutral) incoming.offer(UsbGamepadSession.Completion.Input(ByteArray(20).apply { this[1]=20 },20)) }
        override fun claim(force: Boolean): Boolean { claims+=force;return true }
        override fun startInput()=true
        override fun diagnostics()="IN 81 INT 32B | OUT 02 INT 32B\nXINPUT_INIT=20/20B"
        override fun poll(timeoutMs: Long)=incoming.poll(2,TimeUnit.MILLISECONDS) ?: UsbGamepadSession.Completion.Timeout
        override fun write(packet: ByteArray): Boolean { packets+=packet.clone();incoming.offer(UsbGamepadSession.Completion.Output);return true }
        override fun cancelInput()=Unit
        override fun release()=Unit
        override fun close() { closed=true }
    }
    @Test fun permissionGrantedReceiverProvidesInputAndRumbleWithoutAnAndroidInputDevice() {
        val usb=app.getSystemService(Context.USB_SERVICE) as UsbManager;val device=device()
        shadowOf(usb).addOrUpdateUsbDevice(device,true)
        val io=Io();val states=CopyOnWriteArrayList<GamepadState>();val connected=CopyOnWriteArrayList<ControllerSource.Usb>()
        val driver=UsbDirectController(app,connected={ source,id -> assertNull(id);connected+=source },input={ _,state -> states+=state },
            connectionFactory={ _,_,_,_ -> io })
        try {
            val candidate=driver.candidates().single();assertEquals(0x81,candidate.input);assertEquals(2,candidate.output)
            assertEquals(UsbControllerStatus.OFF,driver.snapshot().status);assertTrue(io.claims.isEmpty())
            driver.enable(candidate)
            await { driver.source!=null };assertEquals(1,connected.size);assertEquals(listOf(false),io.claims)
            io.incoming.offer(UsbGamepadSession.Completion.Input(ByteArray(20).apply { this[1]=20;this[3]=16 },20))
            await { states.any { it.buttons==16 } }
            assertEquals(2L,driver.snapshot().valid)
            driver.test();await { io.packets.any { it[3].toInt()==102 && it[4].toInt()==102 } }
            driver.capture()
            repeat(30) { io.incoming.offer(UsbGamepadSession.Completion.Input(byteArrayOf(1,3,0),3)) }
            await { driver.snapshot().ignored>=30 }
            assertEquals(24,driver.snapshot().capture.size)
            driver.setForeground(false);await { io.closed }
            assertNull(driver.source);assertArrayEquals(UsbXInputProtocol.packet(0,0),io.packets.last())
            assertEquals(UsbControllerStatus.OFF,driver.snapshot().status)
        } finally { driver.close();shadowOf(usb).removeUsbDevice(device) }
    }
    @Test fun missingPermissionNeverOpensTheInterfaceAndUnknownInterfacesAreNotClaimed() {
        val usb=app.getSystemService(Context.USB_SERVICE) as UsbManager
        val supported=device();val hid=device("/dev/bus/usb/001/002",klass=3)
        val one=device("/dev/bus/usb/001/003",protocol=0x81)
        shadowOf(usb).addOrUpdateUsbDevice(supported,false);shadowOf(usb).addOrUpdateUsbDevice(hid,true);shadowOf(usb).addOrUpdateUsbDevice(one,true)
        var opens=0
        val driver=UsbDirectController(app,connectionFactory={ _,_,_,_ -> opens++;Io() })
        try {
            assertEquals(1,driver.candidates().size);driver.enable(driver.candidates().single())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0,opens);assertNull(driver.source)
            assertTrue(driver.snapshot().status in setOf(UsbControllerStatus.PERMISSION,UsbControllerStatus.DENIED))
            driver.disable();assertEquals(UsbControllerStatus.OFF,driver.snapshot().status)
            app.sendBroadcast(Intent(app.packageName+".USB_INPUT_PERMISSION"));shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0,opens);assertNull(driver.source)
        } finally { driver.close();listOf(supported,hid,one).forEach(shadowOf(usb)::removeUsbDevice) }
    }
    @Test fun reopeningTheSameReceiverDoesNotReplayOldQueuedInputOrRemoveTheNewPlayer() {
        val usb=app.getSystemService(Context.USB_SERVICE) as UsbManager;val device=device()
        shadowOf(usb).addOrUpdateUsbDevice(device,true)
        val first=Io();val second=Io();var opens=0
        val router=ControllerRouter();router.beginSession(false)
        val driver=UsbDirectController(app,connected={ source,id -> router.attachUsb(source,id) },
            input={ source,state -> router.slot(source)?.let { router.update(it,state) } },disconnected={ router.remove(it) },
            connectionFactory={ _,_,_,_ -> if(opens++==0) first else second })
        try {
            val candidate=driver.candidates().single();driver.enable(candidate);await { driver.source!=null }
            val old=driver.source!!
            first.incoming.offer(UsbGamepadSession.Completion.Input(ByteArray(20).apply { this[1]=20;this[3]=16 },20))
            Thread.sleep(30) // Keep UI delivery queued until after changing generation.
            driver.enable(candidate);await { driver.source!=null && driver.source!=old }
            second.incoming.offer(UsbGamepadSession.Completion.Input(ByteArray(20).apply { this[1]=20;this[3]=32 },20))
            await { router.physical(0).buttons==32 }
            assertEquals(driver.source,router.sourceFor(0));assertNotEquals(old,router.sourceFor(0));assertTrue(first.closed)
        } finally { driver.close();shadowOf(usb).removeUsbDevice(device) }
    }
    @Test fun permissionResponseMustMatchTheCurrentRequestBeforeOpeningTheReceiver() {
        val usb=app.getSystemService(Context.USB_SERVICE) as UsbManager;val device=device()
        shadowOf(usb).addOrUpdateUsbDevice(device,false)
        val io=Io();var opens=0
        val driver=UsbDirectController(app,connectionFactory={ _,_,_,_ -> opens++;io })
        try {
            val candidate=driver.candidates().single();driver.enable(candidate)
            shadowOf(usb).addOrUpdateUsbDevice(device,true)
            ReflectionHelpers.setField(driver,"pending",Triple(candidate,false,"current-request"))
            val action=app.packageName+".USB_INPUT_PERMISSION"
            app.sendBroadcast(Intent(action).setPackage(app.packageName).putExtra("usb_request","stale-request"))
            shadowOf(Looper.getMainLooper()).idle();assertEquals(0,opens);assertNull(driver.source)
            app.sendBroadcast(Intent(action).setPackage(app.packageName).putExtra("usb_request","current-request"))
            await { driver.source!=null };assertEquals(1,opens);assertEquals(listOf(false),io.claims)
            driver.disable();await { io.closed }
        } finally { driver.close();shadowOf(usb).removeUsbDevice(device) }
    }
    @Test fun inputOwnershipStartsOnlyAfterAValidFrameAndHeldConnectionButtonsNeedRelease() {
        val usb=app.getSystemService(Context.USB_SERVICE) as UsbManager;val device=device()
        shadowOf(usb).addOrUpdateUsbDevice(device,true)
        val io=Io(false);val states=CopyOnWriteArrayList<GamepadState>();var connected=0
        val driver=UsbDirectController(app,connected={ _,_ -> connected++ },input={ _,state -> states+=state },connectionFactory={ _,_,_,_ -> io })
        try {
            driver.enable(driver.candidates().single());await { driver.snapshot().status==UsbControllerStatus.WAITING_INPUT }
            io.incoming.offer(UsbGamepadSession.Completion.Input(byteArrayOf(1,3,0),3))
            await { driver.snapshot().ignored==1L };assertNull(driver.source);assertEquals(0,connected)
            assertEquals(UsbControllerStatus.WAITING_INPUT,driver.snapshot().status)
            assertTrue(driver.snapshot().transportDetails.contains("XINPUT_INIT=20/20B"))
            driver.test();await { io.packets.any { it[3].toInt()==102 } }
            fun button(value: Int)=UsbGamepadSession.Completion.Input(ByteArray(20).apply { this[1]=20;this[3]=value.toByte() },20)
            io.incoming.offer(button(16));await { driver.source!=null && states.isNotEmpty() }
            assertEquals(0,states.last().buttons);assertEquals(0,driver.currentInput().buttons)
            assertEquals(UsbControllerStatus.READY,driver.snapshot().status)
            io.incoming.offer(button(0));await { driver.snapshot().state.buttons==0 }
            io.incoming.offer(button(16));await { states.last().buttons==16 }
            assertEquals(1,connected)
        } finally { driver.close();shadowOf(usb).removeUsbDevice(device) }
    }
    private fun broadcastUsb(action: String,device: UsbDevice) {
        app.sendBroadcast(Intent(action).setPackage(app.packageName).putExtra(UsbManager.EXTRA_DEVICE,device))
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun physicalReplugReauthorizesTheChosenReceiverAtItsNewAddressAndKeepsItsPlayerAndProfile() {
        val usb=app.getSystemService(Context.USB_SERVICE) as UsbManager
        val firstDevice=device();val replacement=device("/dev/bus/usb/001/019")
        shadowOf(usb).addOrUpdateUsbDevice(firstDevice,true)
        val first=Io();val second=Io();var opens=0
        val router=ControllerRouter();router.beginSession(false)
        val driver=UsbDirectController(app,connected={ source,id -> router.attachUsb(source,id) },
            input={ source,state -> router.slot(source)?.let { router.update(it,state) } },disconnected={ router.remove(it) },
            connectionFactory={ _,_,_,_ -> if(opens++==0) first else second })
        try {
            driver.enable(driver.candidates().single(),true);await { driver.source!=null }
            val original=driver.source!!
            shadowOf(usb).removeUsbDevice(firstDevice);broadcastUsb(UsbManager.ACTION_USB_DEVICE_DETACHED,firstDevice)
            await { first.closed && driver.source==null };assertNull(router.sourceFor(0))
            assertEquals(UsbControllerStatus.DISCONNECTED,driver.snapshot().status)
            shadowOf(usb).addOrUpdateUsbDevice(replacement,false)
            broadcastUsb(UsbManager.ACTION_USB_DEVICE_ATTACHED,replacement)
            assertEquals(1,opens);assertNull(driver.source)
            val pending: Triple<UsbControllerDescriptor,Boolean,String> = ReflectionHelpers.getField(driver,"pending")
            assertEquals(driver.candidates().single().source.connection,pending.first.source.connection)
            assertTrue(pending.second)
            shadowOf(usb).addOrUpdateUsbDevice(replacement,true)
            app.sendBroadcast(Intent(app.packageName+".USB_INPUT_PERMISSION").setPackage(app.packageName).putExtra("usb_request",pending.third))
            await { driver.source!=null && driver.source!=original }
            val current=driver.source!!
            assertTrue(current.connection.startsWith(replacement.deviceName));assertEquals(original.profileKey,current.profileKey)
            second.incoming.offer(UsbGamepadSession.Completion.Input(ByteArray(20).apply { this[1]=20;this[3]=32 },20))
            await { router.physical(0).buttons==32 }
            assertEquals(current,router.sourceFor(0));assertEquals(2,opens)
            assertTrue(driver.play(RumbleCommand(0,80,20,0,0,100,0,0),current))
            await { second.packets.any { (it[3].toInt() and 255)==204 && (it[4].toInt() and 255)==51 } }
        } finally { driver.close();listOf(firstDevice,replacement).forEach(shadowOf(usb)::removeUsbDevice) }
    }
    @Test fun backgroundReplugWaitsForTheForegroundAndDisableCancelsAutomaticReconnection() {
        val usb=app.getSystemService(Context.USB_SERVICE) as UsbManager
        val firstDevice=device();val replacement=device("/dev/bus/usb/001/021")
        shadowOf(usb).addOrUpdateUsbDevice(firstDevice,true)
        val connections=CopyOnWriteArrayList<Io>()
        val driver=UsbDirectController(app,connectionFactory={ _,_,_,_ -> Io().also { connections+=it } })
        try {
            driver.enable(driver.candidates().single());await { driver.source!=null }
            driver.setForeground(false);await { connections.single().closed }
            shadowOf(usb).removeUsbDevice(firstDevice);broadcastUsb(UsbManager.ACTION_USB_DEVICE_DETACHED,firstDevice)
            shadowOf(usb).addOrUpdateUsbDevice(replacement,true);broadcastUsb(UsbManager.ACTION_USB_DEVICE_ATTACHED,replacement)
            assertEquals(1,connections.size);assertNull(driver.source)
            driver.setForeground(true);await { driver.source!=null }
            assertEquals(2,connections.size)
            driver.disable();await { connections.last().closed }
            shadowOf(usb).removeUsbDevice(replacement);broadcastUsb(UsbManager.ACTION_USB_DEVICE_DETACHED,replacement)
            shadowOf(usb).addOrUpdateUsbDevice(replacement,true);broadcastUsb(UsbManager.ACTION_USB_DEVICE_ATTACHED,replacement)
            driver.setForeground(true);assertNull(driver.source);assertEquals(2,connections.size)
            assertEquals(UsbControllerStatus.OFF,driver.snapshot().status)
        } finally { driver.close();listOf(firstDevice,replacement).forEach(shadowOf(usb)::removeUsbDevice) }
    }
    @Test fun replugDoesNotPickAnUnknownInterfaceOrGuessBetweenIdenticalReceivers() {
        val usb=app.getSystemService(Context.USB_SERVICE) as UsbManager
        val firstDevice=device();val hid=device("/dev/bus/usb/001/022",klass=3)
        val replacement=device("/dev/bus/usb/001/023");val duplicate=device("/dev/bus/usb/001/024")
        shadowOf(usb).addOrUpdateUsbDevice(firstDevice,true)
        val io=Io();var opens=0
        val driver=UsbDirectController(app,connectionFactory={ _,_,_,_ -> opens++;io })
        try {
            driver.enable(driver.candidates().single());await { driver.source!=null }
            shadowOf(usb).removeUsbDevice(firstDevice);broadcastUsb(UsbManager.ACTION_USB_DEVICE_DETACHED,firstDevice)
            await { io.closed };shadowOf(usb).addOrUpdateUsbDevice(hid,true)
            broadcastUsb(UsbManager.ACTION_USB_DEVICE_ATTACHED,hid);assertEquals(1,opens)
            shadowOf(usb).addOrUpdateUsbDevice(replacement,true);shadowOf(usb).addOrUpdateUsbDevice(duplicate,true)
            broadcastUsb(UsbManager.ACTION_USB_DEVICE_ATTACHED,replacement)
            assertEquals(1,opens);assertNull(driver.source);assertEquals(UsbControllerStatus.BUSY,driver.snapshot().status)
            assertEquals("USB_RECONNECT_AMBIGUOUS",driver.snapshot().detail)
        } finally { driver.close();listOf(firstDevice,hid,replacement,duplicate).forEach(shadowOf(usb)::removeUsbDevice) }
    }
    @Test @Config(sdk=[28],shadows=[SparseInputManagerShadow::class]) fun ownershipTracksTheAndroidIdBeforeAndAfterTheUsbInterfaceIsClaimed() {
        val usb=app.getSystemService(Context.USB_SERVICE) as UsbManager;val receiver=device()
        val manager=ReflectionHelpers.callStaticMethod<InputManager>(InputManager::class.java,"getInstance")
        fun install(vararg ids: Int) {
            val devices=SparseArray<InputDevice>()
            ids.forEach { id ->
                val input=ShadowInputDevice.makeInputDeviceNamed("Demo input").apply {
                    ReflectionHelpers.setField(this,"mId",id);ReflectionHelpers.setField(this,"mVendorId",0x2dc8)
                    ReflectionHelpers.setField(this,"mProductId",0x100)
                    ReflectionHelpers.setField(this,"mSources",InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD)
                }
                devices.put(id,input)
            }
            ReflectionHelpers.setField(manager,"mInputDevices",devices)
        }
        shadowOf(usb).addOrUpdateUsbDevice(receiver,true);install(7)
        val io=Io(false);var attached: Int?=null
        val driver=UsbDirectController(app,connected={ _,id -> attached=id },connectionFactory={ _,_,_,_ -> io })
        try {
            val candidate=driver.candidates().single();assertEquals(7,candidate.androidId)
            driver.enable(candidate);await { driver.snapshot().status==UsbControllerStatus.WAITING_INPUT }
            install(8)
            io.incoming.offer(UsbGamepadSession.Completion.Input(ByteArray(20).apply { this[1]=20 },20))
            await { driver.source!=null };assertEquals(8,attached)
            assertEquals(setOf(7,8),driver.androidAliases());assertTrue(driver.ownsAndroid(7));assertTrue(driver.ownsAndroid(8))
            install(9);assertTrue(driver.ownsAndroid(9));assertEquals(setOf(7,8,9),driver.androidAliases())
            install(9,10);assertFalse(driver.ownsAndroid(10))
            driver.disable();await { io.closed };assertTrue(driver.androidAliases().isEmpty());assertFalse(driver.ownsAndroid(9))
        } finally { driver.close();shadowOf(usb).removeUsbDevice(receiver);install() }
    }
    @Test @Config(sdk=[24]) fun oldAndroidKeepsTheDefaultPathWithoutStartingAUsbWorker() {
        val before=Thread.getAllStackTraces().keys.count { it.name=="xcloud-usb-input" }
        val driver=UsbDirectController(app)
        val descriptor=UsbControllerDescriptor(ControllerSource.Usb("unknown",1,2,0,"Demo"),0x81,2,32)
        driver.enable(descriptor)
        assertEquals(UsbControllerStatus.ANDROID_REQUIRED,driver.snapshot().status)
        driver.setForeground(false);driver.close()
        assertEquals(before,Thread.getAllStackTraces().keys.count { it.name=="xcloud-usb-input" })
    }
}

/** Preserve real Android sparse IDs; Robolectric's stock API-28 shadow assumes 0..size-1 IDs. */
@Implements(InputManager::class)
class SparseInputManagerShadow: org.robolectric.shadows.ShadowInputManager() {
    @RealObject private lateinit var manager: InputManager
    @Implementation override fun getInputDeviceIds(): IntArray {
        val devices=ReflectionHelpers.getField<SparseArray<InputDevice>>(manager,"mInputDevices")
        return IntArray(devices.size()) { devices.keyAt(it) }
    }
}
