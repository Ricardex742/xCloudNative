package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.InputDevice
import android.view.Surface
import com.xcloud.tclnative.settings.GyroMode
import com.xcloud.tclnative.settings.GyroSource
import kotlin.math.hypot

object GyroAim {
    fun active(mode: GyroMode,base: GamepadState)=when(mode) {
        GyroMode.OFF -> false;GyroMode.ALWAYS -> true;GyroMode.LT -> base.leftTrigger>.1f;GyroMode.LB -> base.buttons and 4096!=0
    }
    fun axes(x: Float,y: Float,rotation: Int,sensitivity: Int,z: Float=0f,
             source: GyroSource=GyroSource.DEVICE): Pair<Float,Float> {
        if(!x.isFinite() || !y.isFinite() || !z.isFinite()) return 0f to 0f
        val gain=.80f * sensitivity.coerceIn(25,300)/100f
        val yaw=if(source==GyroSource.CONTROLLER) -z-y*.65f else when(rotation) {
            Surface.ROTATION_90 -> -x-z*.65f
            Surface.ROTATION_270 -> x-z*.65f
            Surface.ROTATION_180 -> y-z*.65f
            else -> -y-z*.65f
        }
        val pitch=if(source==GyroSource.CONTROLLER) x else when(rotation) {
            Surface.ROTATION_90 -> -y
            Surface.ROTATION_270 -> y
            Surface.ROTATION_180 -> x
            else -> -x
        }
        return aim(yaw,gain) to aim(pitch,gain)
    }
    private fun aim(axis: Float,gain: Float) =
        if(kotlin.math.abs(axis)<.025f) 0f else (axis*gain).coerceIn(-1f,1f)
}

/** Some Android drivers expose buttons and their IMU as two InputDevices. */
internal fun controllerMotionNodes(devices: List<InputDevice>, controllerId: Int?): List<InputDevice> {
    val physical = devices.filterNot { it.isVirtual }
    val gamepads = physical.filter { AndroidGamepadPolicy.supportsGamepad(it.sources) }
    val sensors = physical.filter { it.supportsSource(InputDevice.SOURCE_SENSOR) && it !in gamepads }
    fun sameModel(a: InputDevice, b: InputDevice) = a.vendorId == b.vendorId && a.productId == b.productId &&
        a.name.equals(b.name, ignoreCase = true)

    return gamepads.filter { controllerId == null || it.id == controllerId }.flatMap { gamepad ->
        // Two identical controllers can have indistinguishable sensor nodes. Keep their inputs separate.
        val companion = if (gamepads.count { sameModel(gamepad, it) } == 1) sensors.singleOrNull {
            it.vendorId == gamepad.vendorId && it.productId == gamepad.productId &&
                (it.name.equals(gamepad.name, ignoreCase = true) ||
                    it.name.equals("${gamepad.name} Motion Sensors", ignoreCase = true))
        } else null
        listOfNotNull(gamepad, companion)
    }
}

class GyroController(context: Context,private val rotation: () -> Int,private val changed: () -> Unit): SensorEventListener {
    private data class Registration(val manager: SensorManager, val sensor: Sensor, val deviceId: Int? = null)

    private val deviceManager=context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var registration: Registration? = null
    private var source=GyroSource.DEVICE
    private var mode=GyroMode.OFF
    private var sensitivity=100
    private var usingNativeTouch=false
    private var axes=0f to 0f
    var samplesReceived=0L
        private set
    private var motionSamples=0L
    private var lastSensorNode: Int?=null
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val expire=Runnable { if(listening) updateAxes(0f to 0f) }
    var status=UiText.text("Giroscópio: desligado")
        private set
    fun resetDiagnostics() { samplesReceived=0;motionSamples=0;lastSensorNode=null }
    fun diagnostics() = "$status | GYROSCOPE/SOURCE=${source.name}; SAMPLES=$samplesReceived; MOTION=$motionSamples; SENSOR_NODE=${lastSensorNode ?: "n/d"}"
    val listening get() = registration!=null
    fun start(mode: GyroMode,sensitivity: Int,nativeTouch: Boolean,allowNativeTouch: Boolean=false,
              source: GyroSource=GyroSource.DEVICE,controllerId: Int?=null) {
        val next=if(mode==GyroMode.OFF || nativeTouch && !allowNativeTouch) null else findSensor(source,controllerId)
        if (this.mode!=GyroMode.OFF && this.mode==mode && this.sensitivity==sensitivity &&
            this.source==source && registration==next &&
            usingNativeTouch==nativeTouch && (!nativeTouch || allowNativeTouch)) return
        stop()
        this.sensitivity=sensitivity
        this.source=source
        if(mode==GyroMode.OFF) return
        if(nativeTouch && !allowNativeTouch) { status=UiText.text("Giroscópio: teste com toque nativo desativado");return }
        if(next==null) {
            status=if(source==GyroSource.CONTROLLER) UiText.text("Giroscópio: sensor do controle indisponível")
            else UiText.text("Giroscópio: aparelho sem sensor")
            return
        }
        this.mode=mode
        usingNativeTouch=nativeTouch
        val registered=runCatching { next.manager.registerListener(this,next.sensor,10_000,handler) }.getOrDefault(false)
        if(registered) {
            registration=next
            lastSensorNode=next.deviceId
        } else this.mode=GyroMode.OFF
        status=if(registered) UiText.join(UiText.text("Giroscópio: {0}",UiText.nested(mode.label))," • ${next.sensor.name}")
            else UiText.text("Giroscópio: registro recusado pelo Android")
    }
    fun apply(base: GamepadState): GamepadState {
        if(!GyroAim.active(mode,base)) return base
        val magnitude=hypot(axes.first,axes.second).coerceAtMost(1f)
        if(magnitude==0f) return base
        // Counter a small in-game stick dead zone without adding movement at rest.
        val scale=1f + .08f * (1f - magnitude) / magnitude
        return base.copy(rightX=(base.rightX+axes.first*scale).coerceIn(-1f,1f),
            rightY=(base.rightY+axes.second*scale).coerceIn(-1f,1f))
    }
    override fun onSensorChanged(event: SensorEvent) {
        val sensor=registration?.sensor ?: return
        if(mode==GyroMode.OFF || event.sensor!==sensor || event.values.size<3) return
        samplesReceived++
        val values=event.values
        val biased=sensor.type==Sensor.TYPE_GYROSCOPE_UNCALIBRATED && values.size>=6
        val x=values[0] - if(biased) values[3] else 0f
        val y=values[1] - if(biased) values[4] else 0f
        val z=values[2] - if(biased) values[5] else 0f
        val next=GyroAim.axes(x,y,rotation(),sensitivity,z,source)
        if(next.first!=0f || next.second!=0f) motionSamples++
        fun smooth(last: Float, value: Float) = if(last * value <= 0f) value else last + (value - last) * .4f
        updateAxes(smooth(axes.first,next.first) to smooth(axes.second,next.second))
        handler.removeCallbacks(expire)
        if(axes.first!=0f || axes.second!=0f) handler.postDelayed(expire,100)
    }
    private fun updateAxes(next: Pair<Float,Float>) {
        if(axes==next) return
        axes=next
        changed()
    }
    override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) { }
    fun stop() {
        registration?.manager?.let { runCatching { it.unregisterListener(this) } }
        registration=null
        handler.removeCallbacks(expire)
        mode=GyroMode.OFF
        axes=0f to 0f
        status=UiText.text("Giroscópio: desligado")
    }
    private fun findSensor(source: GyroSource,controllerId: Int?): Registration? {
        fun find(manager: SensorManager?,deviceId: Int? = null): Registration? = manager?.let {
            (it.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: it.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED))
                ?.let { sensor -> Registration(manager, sensor, deviceId) }
        }
        if(source==GyroSource.DEVICE) return find(deviceManager)
        if(Build.VERSION.SDK_INT<31) return null
        val devices=InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice).toList()
        return controllerMotionNodes(devices,controllerId).firstNotNullOfOrNull { device ->
            runCatching { find(device.sensorManager, device.id) }.getOrNull()
        }
    }
}
