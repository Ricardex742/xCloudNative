package com.xcloud.tclnative.webrtc

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.*
import android.view.InputDevice
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Opt-in, app-local Xbox 360/XInput transport. No ADB, native daemon or global input injection. */
internal class UsbDirectController(context: Context,
    private val connected: (ControllerSource.Usb,Int?) -> Unit={ _,_ -> },
    private val input: (ControllerSource.Usb,GamepadState) -> Unit={ _,_ -> },
    private val disconnected: (ControllerSource.Usb) -> Unit={},
    private val connectionFactory: ((UsbDevice,UsbInterface,UsbEndpoint,UsbEndpoint) -> UsbGamepadSession.Io)?=null): UsbControllerPort,AutoCloseable {
    private val app=context.applicationContext
    private val manager=app.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val main=Handler(Looper.getMainLooper())
    private val executorDelegate=lazy { Executors.newSingleThreadExecutor { task ->
        Thread(task,"xcloud-usb-input").apply { isDaemon=true }
    } }
    private val executor by executorDelegate
    private val generation=AtomicInteger()
    private var pendingFrames=UsbInputQueue()
    private val packets=AtomicLong();private val valid=AtomicLong();private val ignored=AtomicLong();private val writes=AtomicLong()
    @Volatile private var session: UsbGamepadSession?=null
    @Volatile private var closed=false
    @Volatile private var foreground=true
    @Volatile private var state=GamepadState()
    @Volatile private var effectiveState=GamepadState()
    @Volatile private var transport: ControllerSource.Usb?=null
    @Volatile private var status=UsbControllerStatus.OFF
    @Volatile private var detail=""
    @Volatile var source: ControllerSource.Usb?=null;private set
    private val ownedAndroidAliases=mutableSetOf<Int>()
    private var selected: UsbControllerDescriptor?=null
    private var selectedExclusive=false
    private var pending: Triple<UsbControllerDescriptor,Boolean,String>?=null
    private var registered=false
    private val action="${app.packageName}.USB_INPUT_PERMISSION"
    private val captureLock=Any()
    private val raw=java.util.ArrayDeque<String>()
    private var captureUntil=0L
    private var deliveries=0L;private var deliveryTotal=0L;private var deliveryPeak=0L
    private data class Native(val device: UsbDevice,val intf: UsbInterface,val input: UsbEndpoint,val output: UsbEndpoint)
    private fun devices()=runCatching { manager?.deviceList?.values?.toList().orEmpty() }.getOrDefault(emptyList())
    private fun native(): List<Native> = devices().mapNotNull { device ->
        (0 until device.interfaceCount).mapNotNull { index ->
            val intf=device.getInterface(index)
            val endpoints=(0 until intf.endpointCount).map(intf::getEndpoint)
            val incoming=endpoints.filter { it.direction==UsbConstants.USB_DIR_IN && it.type==UsbConstants.USB_ENDPOINT_XFER_INT }
            val outgoing=endpoints.filter { it.direction==UsbConstants.USB_DIR_OUT && it.type==UsbConstants.USB_ENDPOINT_XFER_INT }
            if(incoming.size==1 && incoming.single().maxPacketSize in 20..512 && UsbXInputProtocol.supported(
                    intf.interfaceClass,intf.interfaceSubclass,intf.interfaceProtocol,intf.alternateSetting,
                    true,outgoing.map { it.maxPacketSize })) Native(device,intf,incoming.single(),outgoing.single()) else null
        }.singleOrNull()
    }
    override fun candidates(): List<UsbControllerDescriptor> {
        val usb=devices();val android=InputDevice.getDeviceIds().map(InputDevice::getDevice).filterNotNull().filter(UsbControllerRumble::isGamepad)
        return native().map { value ->
            val device=value.device
            val name=runCatching { device.productName }.getOrNull().orEmpty().take(80).map { if(it.isISOControl()) ' ' else it }.joinToString("")
            UsbControllerDescriptor(ControllerSource.Usb("${device.deviceName}:${value.intf.id}",device.vendorId,device.productId,value.intf.id,
                name.ifBlank { "USB/XInput" }),value.input.address,value.output.address,value.input.maxPacketSize,
                UsbXInputProtocol.inputDeviceId(device.vendorId,device.productId,usb.map { it.vendorId to it.productId },
                    android.map { Triple(it.id,it.vendorId,it.productId) }))
        }
    }
    fun androidAliases(): Set<Int> = ownedAndroidAliases.toSet()
    fun ownsAndroid(id: Int): Boolean {
        val usb=source ?: return false
        val device=InputDevice.getDevice(id)
        if(id in ownedAndroidAliases && (device==null || device.vendorId==usb.vendor && device.productId==usb.product)) return true
        // Exclusive claiming or a driver refresh can replace the Android InputDevice ID.
        if(device!=null && device.vendorId==usb.vendor && device.productId==usb.product && currentAndroidPeer(usb)==id) {
            ownedAndroidAliases+=id;return true
        }
        return false
    }
    private fun currentAndroidPeer(usb: ControllerSource.Usb): Int? = UsbXInputProtocol.inputDeviceId(usb.vendor,usb.product,
        devices().map { it.vendorId to it.productId },InputDevice.getDeviceIds().map(InputDevice::getDevice).filterNotNull()
            .filter(UsbControllerRumble::isGamepad).map { Triple(it.id,it.vendorId,it.productId) })
    fun currentInput()=effectiveState
    override fun descriptors(): String = devices().joinToString("\n") { device ->
        "%04x:%04x".format(device.vendorId,device.productId)+"\n"+(0 until device.interfaceCount).joinToString("\n") { index ->
            val intf=device.getInterface(index)
            "${intf.id}: %02x/%02x/%02x • ".format(intf.interfaceClass,intf.interfaceSubclass,intf.interfaceProtocol)+
                (0 until intf.endpointCount).joinToString(" ") { endpoint ->
                    val ep=intf.getEndpoint(endpoint);"%02x/%d/%dB".format(ep.address,ep.type,ep.maxPacketSize)
                }
        }
    }+"\n"+InputDevice.getDeviceIds().map(InputDevice::getDevice).filterNotNull().filter(UsbControllerRumble::isGamepad).joinToString("\n") {
        "AndroidInputDevice ${it.id} • %04x:%04x • sources=%08x".format(it.vendorId,it.productId,it.sources)
    }
    override fun snapshot()=UsbControllerSnapshot(status,source ?: transport,packets.get(),valid.get(),ignored.get(),writes.get(),state,detail,
        synchronized(captureLock) { raw.toList() },if(deliveries>0) deliveryTotal.toDouble()/deliveries else 0.0,
        deliveryPeak,pendingFrames.coalesced,pendingFrames.overflows,session?.diagnostics().orEmpty())
    override fun enable(candidate: UsbControllerDescriptor,exclusive: Boolean) {
        if(closed || !foreground) return
        if(Build.VERSION.SDK_INT<26) { status=UsbControllerStatus.ANDROID_REQUIRED;return }
        val fresh=candidates().firstOrNull { it.source.connection==candidate.source.connection }
        val value=native().firstOrNull { "${it.device.deviceName}:${it.intf.id}"==candidate.source.connection }
        if(fresh==null || value==null) { status=UsbControllerStatus.UNAVAILABLE;return }
        // Refuse an ambiguous Android duplicate rather than consuming input from another player's pad.
        val matches=InputDevice.getDeviceIds().map(InputDevice::getDevice).filterNotNull().filter {
            UsbControllerRumble.isGamepad(it) && it.vendorId==fresh.source.vendor && it.productId==fresh.source.product }
        if(matches.isNotEmpty() && fresh.androidId==null) { status=UsbControllerStatus.BUSY;detail="USB_ANDROID_AMBIGUOUS";return }
        register()
        if(!registered) return
        deactivate(false)
        selected=fresh;selectedExclusive=exclusive
        if(manager?.hasPermission(value.device)!=true) {
            val request=Triple(fresh,exclusive,java.util.UUID.randomUUID().toString())
            pending=request;status=UsbControllerStatus.PERMISSION
            try { manager?.requestPermission(value.device,PendingIntent.getBroadcast(app,request.third.hashCode(),
                Intent(action).setPackage(app.packageName).putExtra("usb_request",request.third),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)) }
            catch(_: RuntimeException) { pending=null;status=UsbControllerStatus.DENIED }
            return
        }
        start(fresh,exclusive)
    }
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            if(closed) return
            if(intent.action==UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val selection=selected ?: return
                if(selection.source.connection !in candidates().map { it.source.connection }) {
                    // Keep the user's opt-in policy. USB bus addresses and Android IDs change on replug.
                    deactivate(false);status=UsbControllerStatus.DISCONNECTED
                }
            } else if(intent.action==UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val device=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if(device!=null) reconnectSelection(true,device)
            } else if(intent.action==action) {
                val request=pending ?: return
                if(intent.getStringExtra("usb_request")!=request.third) return
                pending=null
                val found=native().firstOrNull { "${it.device.deviceName}:${it.intf.id}"==request.first.source.connection }
                if(found!=null && foreground && manager?.hasPermission(found.device)==true) enable(request.first,request.second)
                else { selected=null;status=UsbControllerStatus.DENIED }
            }
        }
    }
    // The legacy branch runs below API 33. The API 33+ registration is explicitly non-exported.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun register() {
        if(registered) return
        val filter=IntentFilter(action).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        try {
            if(Build.VERSION.SDK_INT>=33) app.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver,filter)
            registered=true
        } catch(_: RuntimeException) { status=UsbControllerStatus.FAILED;detail="USB_RECEIVER" }
    }
    private fun reconnectSelection(requestPermission: Boolean,attachedDevice: UsbDevice?=null) {
        val previous=selected ?: return
        if(closed || !foreground || pending!=null || transport!=null || source!=null || status==UsbControllerStatus.OPENING) return
        fun sameReceiver(candidate: UsbControllerDescriptor)=candidate.source.vendor==previous.source.vendor &&
            candidate.source.product==previous.source.product && candidate.source.intf==previous.source.intf &&
            candidate.input==previous.input && candidate.output==previous.output && candidate.packetSize==previous.packetSize
        val matches=candidates().filter(::sameReceiver)
        if(matches.size!=1) {
            status=if(matches.isEmpty()) UsbControllerStatus.DISCONNECTED else UsbControllerStatus.BUSY
            detail=if(matches.size>1) "USB_RECONNECT_AMBIGUOUS" else ""
            return
        }
        val candidate=matches.single()
        val value=native().firstOrNull { "${it.device.deviceName}:${it.intf.id}"==candidate.source.connection } ?: return
        if(attachedDevice!=null && value.device.deviceName!=attachedDevice.deviceName) return
        if(manager?.hasPermission(value.device)==true || requestPermission) enable(candidate,selectedExclusive)
        else status=UsbControllerStatus.DISCONNECTED
    }
    private fun start(candidate: UsbControllerDescriptor,exclusive: Boolean) {
        val token=generation.incrementAndGet();session?.stop();pendingFrames.clear()
        val frames=UsbInputQueue();pendingFrames=frames
        val draining=AtomicBoolean()
        status=UsbControllerStatus.OPENING;detail=""
        deliveries=0;deliveryTotal=0;deliveryPeak=0
        val runtime=candidate.source.copy(connection="${candidate.source.connection}@$token")
        executor.execute {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            if(closed || !foreground || generation.get()!=token) return@execute
            val value=native().firstOrNull { "${it.device.deviceName}:${it.intf.id}"==candidate.source.connection }
            if(value==null || manager?.hasPermission(value.device)!=true) {
                main.post { if(generation.get()==token) status=UsbControllerStatus.UNAVAILABLE };return@execute
            }
            packets.set(0);valid.set(0);ignored.set(0);writes.set(0)
            synchronized(captureLock) { raw.clear();captureUntil=0 }
            var current: UsbGamepadSession?=null
            var emitted: GamepadState?=null
            var initialButtons: Int?=null
            try {
                if(Build.VERSION.SDK_INT<26) return@execute
                val base=connectionFactory?.invoke(value.device,value.intf,value.input,value.output) ?: run {
                    val connection=manager?.openDevice(value.device) ?: error("USB_OPEN_FAILED")
                    try { AndroidUsbGamepadIo(connection,value.intf,value.input,value.output) }
                    catch(error: Exception) { connection.close();throw error }
                }
                val io=object: UsbGamepadSession.Io by base {
                    override fun write(packet: ByteArray)=base.write(packet).also { if(it) writes.incrementAndGet() }
                }
                current=UsbGamepadSession(io,exclusive,SystemClock::uptimeMillis,packet@ { bytes,length,parsed,at ->
                    if(generation.get()!=token || !foreground || closed) return@packet
                    packets.incrementAndGet()
                    synchronized(captureLock) {
                        if(at<captureUntil && raw.size<24) raw.addLast(buildString {
                            val hex="0123456789abcdef"
                            repeat(length.coerceAtMost(32)) { index ->
                                if(index>0) append(' ')
                                val value=bytes[index].toInt() and 255;append(hex[value ushr 4]);append(hex[value and 15])
                            }
                        })
                    }
                    if(parsed==null) ignored.incrementAndGet() else {
                        valid.incrementAndGet();state=parsed
                        if(initialButtons==null) {
                            initialButtons=parsed.buttons
                            main.post {
                                if(!closed && foreground && generation.get()==token) {
                                    source=runtime;status=UsbControllerStatus.READY;detail=""
                                    ownedAndroidAliases.clear()
                                    candidate.androidId?.let { ownedAndroidAliases+=it }
                                    val currentAndroidId=currentAndroidPeer(runtime)
                                    currentAndroidId?.let { ownedAndroidAliases+=it }
                                    connected(runtime,currentAndroidId ?: candidate.androidId)
                                }
                            }
                        }
                        initialButtons=initialButtons!! and parsed.buttons
                        val filtered=parsed.copy(buttons=parsed.buttons and initialButtons!!.inv())
                        effectiveState=filtered
                        if(filtered!=emitted) {
                            emitted=filtered;frames.offer(filtered,at);scheduleDrain(token,runtime,frames,draining)
                        }
                    }
                },{
                    main.post {
                        if(!closed && foreground && generation.get()==token) {
                            transport=runtime;status=UsbControllerStatus.WAITING_INPUT;detail="USB_INPUT_PENDING"
                        }
                    }
                })
                session=current
                if(generation.get()!=token || !foreground || closed) current.stop()
                current.run()
            } catch(error: Exception) {
                main.post { if(generation.get()==token) {
                    detail=error.message?.takeIf { it.startsWith("USB_") }?.take(80) ?: "USB_IO"
                    status=if(detail=="USB_INTERFACE_BUSY") UsbControllerStatus.BUSY else UsbControllerStatus.FAILED
                } }
            } finally {
                if(session===current) session=null
                main.post {
                    if(source==runtime) { source=null;ownedAndroidAliases.clear();state=GamepadState() }
                    if(transport==runtime) { transport=null;effectiveState=GamepadState() }
                    disconnected(runtime)
                }
            }
        }
    }
    private fun scheduleDrain(token: Int,runtime: ControllerSource.Usb,frames: UsbInputQueue,draining: AtomicBoolean) {
        if(draining.compareAndSet(false,true)) main.post {
            draining.set(false)
            if(generation.get()!=token || source!=runtime || !foreground || closed) { frames.clear();return@post }
            repeat(16) {
                val frame=frames.poll() ?: return@post
                val age=(SystemClock.uptimeMillis()-frame.at).coerceAtLeast(0)
                deliveries++;deliveryTotal+=age;deliveryPeak=maxOf(deliveryPeak,age)
                input(runtime,frame.state)
            }
            // A bounded batch yields to the UI; the next batch retains button transitions.
            scheduleDrain(token,runtime,frames,draining)
        }
    }
    fun play(command: RumbleCommand,target: ControllerSource.Usb): Boolean {
        if(transport!=target || !foreground || closed) return false
        session?.rumble?.set(command,SystemClock.uptimeMillis()) ?: return false
        return true
    }
    fun stopRumble() { session?.rumble?.stop() }
    override fun test() { transport?.let { play(RumbleCommand(0,40,40,0,0,250,0,0),it) } }
    override fun capture() { synchronized(captureLock) { raw.clear();captureUntil=SystemClock.uptimeMillis()+10_000 } }
    private fun deactivate(clearSelection: Boolean) {
        generation.incrementAndGet();pending=null;session?.stop();pendingFrames.clear()
        if(clearSelection) selected=null
        val previous=source;source=null;transport=null;ownedAndroidAliases.clear();state=GamepadState();effectiveState=GamepadState()
        previous?.let(disconnected)
    }
    override fun disable(done: () -> Unit) {
        if(closed) { done();return }
        deactivate(true);status=UsbControllerStatus.OFF
        if(executorDelegate.isInitialized()) executor.execute { main.post(done) } else done()
    }
    fun setForeground(value: Boolean) {
        foreground=value
        if(!value) { deactivate(false);status=UsbControllerStatus.OFF }
        // A background return never opens a permission dialog. Replug can have assigned a new bus path.
        else reconnectSelection(false)
    }
    override fun close() {
        if(closed) return
        closed=true;deactivate(true);status=UsbControllerStatus.OFF
        if(registered) { app.unregisterReceiver(receiver);registered=false }
        if(executorDelegate.isInitialized()) executor.shutdown()
    }
}
