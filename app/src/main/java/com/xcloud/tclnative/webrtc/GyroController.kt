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

object GyroAim {
    fun active(mode: GyroMode,base: GamepadState)=when(mode) {
        GyroMode.OFF -> false;GyroMode.ALWAYS -> true;GyroMode.LT -> base.leftTrigger>.1f;GyroMode.LB -> base.buttons and 4096!=0
    }
    fun axes(x: Float,y: Float,rotation: Int,sensitivity: Int,z: Float=0f,
             source: GyroSource=GyroSource.DEVICE): Pair<Float,Float> {
        if(!x.isFinite() || !y.isFinite() || !z.isFinite()) return 0f to 0f
        val gain=sensitivity.coerceIn(25,300)/200f
        val yaw=if(source==GyroSource.CONTROLLER) -z-y*.65f else when(rotation) {
            Surface.ROTATION_90 -> -x-z*.65f
            Surface.ROTATION_270 -> x-z*.65f
            Surface.ROTATION_180 -> -y-z*.65f
            else -> y-z*.65f
        }
        val pitch=if(source==GyroSource.CONTROLLER) x else when(rotation) {
            Surface.ROTATION_90 -> y
            Surface.ROTATION_270 -> -y
            Surface.ROTATION_180 -> -x
            else -> x
        }
        return aim(yaw,gain) to aim(pitch,gain)
    }
    private fun aim(axis: Float,gain: Float) =
        if(kotlin.math.abs(axis)<.025f) 0f else (axis*gain).coerceIn(-1f,1f)
}
class GyroController(context: Context,private val rotation: () -> Int,private val changed: () -> Unit): SensorEventListener {
    private val deviceManager=context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var listeningManager: SensorManager?=null
    private var listeningSensor: Sensor?=null
    private var source=GyroSource.DEVICE
    private var mode=GyroMode.OFF
    private var sensitivity=100
    private var usingNativeTouch=false
    private var axes=0f to 0f
    var samplesReceived=0L
        private set
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val expire=Runnable { if(mode!=GyroMode.OFF) { axes=0f to 0f;changed() } }
    var status=UiText.text("Giroscópio: desligado")
        private set
    fun resetDiagnostics() { samplesReceived=0 }
    fun diagnostics() = "$status | GYROSCOPE/SOURCE=${source.name}; SAMPLES=$samplesReceived"
    val listening get() = listeningSensor!=null
    fun start(mode: GyroMode,sensitivity: Int,nativeTouch: Boolean,allowNativeTouch: Boolean=false,
              source: GyroSource=GyroSource.DEVICE,controllerId: Int?=null) {
        val manager=if(mode==GyroMode.OFF || nativeTouch && !allowNativeTouch) null else sensorManager(source,controllerId)
        val sensor=manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (this.mode!=GyroMode.OFF && this.mode==mode && this.sensitivity==sensitivity &&
            this.source==source && listeningManager===manager && listeningSensor===sensor &&
            usingNativeTouch==nativeTouch && (!nativeTouch || allowNativeTouch)) return
        stop()
        this.sensitivity=sensitivity
        this.source=source
        if(mode==GyroMode.OFF) return
        if(nativeTouch && !allowNativeTouch) { status=UiText.text("Giroscópio: teste com toque nativo desativado");return }
        if(sensor==null) {
            status=if(source==GyroSource.CONTROLLER) UiText.text("Giroscópio: sensor do controle indisponível")
            else UiText.text("Giroscópio: aparelho sem sensor")
            return
        }
        this.mode=mode
        usingNativeTouch=nativeTouch
        val registered=runCatching { manager?.registerListener(this,sensor,10_000,handler)==true }.getOrDefault(false)
        if(registered) {
            listeningManager=manager
            listeningSensor=sensor
        } else this.mode=GyroMode.OFF
        status=if(registered) UiText.join(UiText.text("Giroscópio: {0}",UiText.nested(mode.label)),(if(nativeTouch) UiText.text(" • toque nativo experimental") else "")) else UiText.text("Giroscópio: registro recusado pelo Android")
    }
    fun apply(base: GamepadState): GamepadState = if(!GyroAim.active(mode,base)) base else base.copy(
        rightX=(base.rightX+axes.first).coerceIn(-1f,1f),rightY=(base.rightY+axes.second).coerceIn(-1f,1f))
    override fun onSensorChanged(event: SensorEvent) {
        if(mode==GyroMode.OFF || (event.sensor!=null && event.sensor!==listeningSensor)) return
        samplesReceived++
        axes=GyroAim.axes(event.values[0],event.values[1],rotation(),sensitivity,event.values[2],source)
        changed()
        handler.removeCallbacks(expire);handler.postDelayed(expire,100)
    }
    override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) { }
    fun stop() {
        listeningManager?.unregisterListener(this)
        listeningManager=null
        listeningSensor=null
        handler.removeCallbacks(expire)
        mode=GyroMode.OFF
        axes=0f to 0f
        status=UiText.text("Giroscópio: desligado")
    }
    private fun sensorManager(source: GyroSource,controllerId: Int?): SensorManager? {
        if(source==GyroSource.DEVICE) return deviceManager
        if(Build.VERSION.SDK_INT<31) return null
        return (controllerId?.let { sequenceOf(it) } ?: InputDevice.getDeviceIds().asSequence())
            .mapNotNull(InputDevice::getDevice).filter { !it.isVirtual && AndroidGamepadPolicy.supportsGamepad(it.sources) }
            .mapNotNull { it.sensorManager }.firstOrNull { it.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!=null }
    }
}
