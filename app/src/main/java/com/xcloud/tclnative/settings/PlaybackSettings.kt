package com.xcloud.tclnative.settings

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import com.xcloud.tclnative.webrtc.StreamResolution

enum class AudioPlaybackMode(private val source: String) {
    STANDARD("Padrão"), LOW_LATENCY("Baixa latência — experimental");
    val label get() = UiText.text(source)
}

enum class VideoMode(private val sourceLabel: String, val lowLatency: Boolean) {
    LOW_LATENCY("Menor atraso", true), ADAPTIVE("Adaptativo", false),
    COMPATIBILITY("Compatibilidade — TVs mais fracas", false);
    val label: String get()=UiText.text(sourceLabel)
}
enum class VideoCodecMode(private val sourceLabel: String) {
    H264("H.264 — padrão"), H264_BASELINE("H.264 leve — Baseline (720p)"), H264_MAIN("H.264 Main — experimental"),
    H264_HIGH_HW("H.264 High — experimental"), H265("H.265/HEVC Main — experimental"),
    VP9("VP9 — experimental"), AV1("AV1 — experimental");
    val label: String get()=UiText.text(sourceLabel)
}
enum class FsrMode(private val sourceLabel: String) { OFF("Desativado"), FSR1("AMD FSR 1.0.2");
    val label: String get()=UiText.text(sourceLabel)
}
enum class FsrOutput(private val sourceLabel: String) {
    AUTO("Automático"), QHD_1440("1440p — 2560×1440"), UHD_4K("4K — 3840×2160 (experimental)");
    val label: String get()=UiText.text(sourceLabel)
}
enum class TouchMode(private val sourceLabel: String) {
    AUTO("Automático — detectar dispositivo e jogo"), OFF("Desativado — controle físico"), VIRTUAL("Exclusivo — controle virtual"),
    NATIVE("Toque nativo Xbox — experimental");
    val label: String get()=UiText.text(sourceLabel)
}

enum class VideoAspect(private val sourceLabel: String, val ratio: Double? = null, val stretch: Boolean = false) {
    ORIGINAL("Original — manter proporção"),
    RATIO_16_9("16:9 — padrão",16.0/9,true),
    RATIO_18_9("18:9",18.0/9,true),
    RATIO_21_9("21:9",21.0/9,true),
    RATIO_16_10("16:10",16.0/10,true),
    RATIO_4_3("4:3",4.0/3,true),
    STRETCH("Esticar — preencher a tela",stretch=true),
    ZOOM("Zoom — preencher com corte");
    val label: String get()=UiText.text(sourceLabel)
}

data class PlaybackSettings(
    val videoMode: VideoMode = VideoMode.LOW_LATENCY,
    val resolution: StreamResolution = StreamResolution.HD_1080_HQ,
    val nativeUpscaling: Boolean = true,
    val aspect: VideoAspect = VideoAspect.RATIO_16_9,
    val localCoOp: Boolean = false,
    val rumble: Boolean = true,
    val touchMode: TouchMode = TouchMode.AUTO,
    val sharpness: Int = 0,
    val saturation: Int = 100,
    val sessionReport: Boolean = true,
    val videoCodec: VideoCodecMode = VideoCodecMode.H264,
    val fsr: FsrMode = FsrMode.OFF,
    val fsrSharpness: Int = 50,
    val brightness: Int = 0,val contrast: Int = 100,
    val ipPreference: IpPreference = IpPreference.AUTO,
    val desktopInput: DesktopInputMode = DesktopInputMode.OFF,val mouseSensitivity: Int = 100,
    val gyroMode: GyroMode = GyroMode.OFF,val gyroSensitivity: Int = 100,
    val microphone: Boolean = false,val audioGain: Int = 100,val touchRumble: Boolean = false,
    val gyroNativeTouch: Boolean = false,
    val fsrOutput: FsrOutput = FsrOutput.AUTO,
    val imageTreatment: ImageTreatmentSettings = ImageTreatmentSettings(),
    val presentationPacing: PresentationPacing = PresentationPacing.OFF,
    val reconstruction: ReconstructionSettings = ReconstructionSettings(),
    val audioMode: AudioPlaybackMode = AudioPlaybackMode.STANDARD,
    val imageLab: ImageLabSettings = ImageLabSettings(),
    val imageComparison: ImageComparison = ImageComparison.PROCESSED,
    val gyroSource: GyroSource = GyroSource.DEVICE,
    val matchDisplayMode: Boolean = true,
    val tryAlternateDecoder: Boolean = false,
    val tryMotionFence: Boolean = false,
    val tryIdleGamepadCadence: Boolean = false,
    val trySkipAudioSamples: Boolean = false
) {
    fun effectiveImageComparison(): ImageComparison = when {
        imageComparison!=ImageComparison.PROCESSED -> imageComparison
        imageLab.enabled -> imageLab.comparison
        else -> ImageComparison.PROCESSED
    }
    fun forSession(): PlaybackSettings {
        val selected=if(videoCodec==VideoCodecMode.H264_BASELINE || videoMode==VideoMode.COMPATIBILITY)
            copy(resolution=StreamResolution.HD_720) else this
        return when(videoMode) {
            VideoMode.COMPATIBILITY -> selected.copy(imageTreatment=ImageTreatmentSettings(),
                presentationPacing=PresentationPacing.OFF,fsr=FsrMode.OFF,imageLab=ImageLabSettings(),imageComparison=ImageComparison.PROCESSED,
                tryAlternateDecoder=false,tryMotionFence=false,tryIdleGamepadCadence=false,trySkipAudioSamples=false)
            VideoMode.LOW_LATENCY -> selected.copy(presentationPacing=PresentationPacing.OFF)
            else -> selected
        }
    }
}

class PlaybackSettingsStore(context: Context,
                            device: DevicePerformancePolicy = DevicePerformancePolicy.read(context)) {
    private val prefs = context.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE)
    // Older installs may have only legacy keys. Never replace any existing playback choices.
    private val defaults = if(device.constrainedMemory && prefs.all.keys.all { it=="game_hud_migrated" })
        PlaybackSettings(resolution=StreamResolution.HD_720,videoCodec=VideoCodecMode.H264_BASELINE)
        else PlaybackSettings()
    init {
        if(prefs.getString("video_codec",null) in setOf("H264_HIGH","HEVC"))
            prefs.edit().putString("video_codec",VideoCodecMode.H264.name).apply()
        if(prefs.getString("touch_mode",null)=="WEB_NATIVE") prefs.edit().putString("touch_mode",TouchMode.NATIVE.name).putBoolean("game_hud_migrated",true).commit()
        if(!prefs.getBoolean("game_hud_migrated",false)) {
            val editor=prefs.edit().putBoolean("game_hud_migrated",true)
            if(prefs.getString("touch_mode",null)==TouchMode.NATIVE.name) editor.putString("touch_mode",TouchMode.VIRTUAL.name)
            editor.apply()
        }
        synchronized(writes) {
            val previous = prefs.getString("fsr_mode", null)
            if (previous == "SGSR1" || previous == "RECONSTRUCTION") {
                val editor = prefs.edit().putString("fsr_mode", FsrMode.FSR1.name)
                if (prefs.getString("image_sharpening", ImageSharpening.LEGACY.name) == ImageSharpening.LEGACY.name)
                    editor.putString("image_sharpening", ImageSharpening.RCAS_LUMA.name)
                val comparison=prefs.getString("image_comparison",null)
                if (previous=="RECONSTRUCTION" && !prefs.contains("reconstruction_strength"))
                    editor.putInt("reconstruction_strength",50)
                if (previous=="RECONSTRUCTION" && comparison in setOf("ORIGINAL","SPLIT") &&
                    prefs.getString("image_comparison_all",ImageComparison.PROCESSED.name)==ImageComparison.PROCESSED.name &&
                    !prefs.getBoolean("image_lab_enabled",false)) editor.putString("image_comparison_all",comparison)
                editor.remove("image_comparison")
                editor.apply()
            }
            if (previous != null && !prefs.getBoolean(FSR_BASE_PROFILE_MIGRATED,false)) {
                val current = read()
                val migrated = if (previous == "RECONSTRUCTION") current else ImageProfiles.migrateFsrPreset(current)
                val editor = prefs.edit().putBoolean(FSR_BASE_PROFILE_MIGRATED,true)
                if (current != migrated) editor.putInt("fsr_sharpness",migrated.fsrSharpness)
                    .putInt("reconstruction_strength",migrated.reconstruction.strength)
                    .putInt("image_temporal",migrated.imageTreatment.temporal)
                editor.apply()
            }
        }
    }
    fun read(): PlaybackSettings {
        val legacy = if (prefs.getBoolean("low_latency_video",true)) VideoMode.LOW_LATENCY else VideoMode.ADAPTIVE
        return PlaybackSettings(
            VideoMode.entries.firstOrNull { it.name == prefs.getString("video_mode",null) } ?: legacy,
            StreamResolution.entries.firstOrNull { it.name == prefs.getString("resolution",null) } ?: defaults.resolution,
            prefs.getBoolean("native_upscaling",true),
            VideoAspect.entries.firstOrNull { it.name == prefs.getString("aspect",null) } ?: VideoAspect.RATIO_16_9,
            prefs.getBoolean("local_coop",false),prefs.getBoolean("rumble",true),
            TouchMode.entries.firstOrNull { it.name==prefs.getString("touch_mode",null) } ?: TouchMode.AUTO,
            prefs.getInt("sharpness",0).coerceIn(0,100),prefs.getInt("saturation",100).coerceIn(0,200),prefs.getBoolean("session_report",true),
            VideoCodecMode.entries.firstOrNull { it.name==prefs.getString("video_codec",null) } ?: defaults.videoCodec,
            FsrMode.entries.firstOrNull { it.name==prefs.getString("fsr_mode",null) } ?: FsrMode.OFF,
            prefs.getInt("fsr_sharpness",50).coerceIn(0,100),
            prefs.getInt("brightness",0).coerceIn(-50,50),prefs.getInt("contrast",100).coerceIn(50,150),
            IpPreference.entries.firstOrNull { it.name==prefs.getString("ip_preference",null) } ?: IpPreference.AUTO,
            DesktopInputMode.entries.firstOrNull { it.name==prefs.getString("desktop_input",null) } ?: DesktopInputMode.OFF,
            prefs.getInt("mouse_sensitivity",100).coerceIn(25,300),
            GyroMode.entries.firstOrNull { it.name==prefs.getString("gyro_mode",null) } ?: GyroMode.OFF,
            prefs.getInt("gyro_sensitivity",100).coerceIn(25,300),prefs.getBoolean("microphone",false),
            prefs.getInt("audio_gain",100).coerceIn(0,300),prefs.getBoolean("touch_rumble",false),prefs.getBoolean("gyro_native_touch",false),
            FsrOutput.entries.firstOrNull { it.name==prefs.getString("fsr_output",null) } ?: FsrOutput.AUTO,
            ImageTreatmentSettings(
                ImageSharpening.entries.firstOrNull { it.name==prefs.getString("image_sharpening",null) } ?: ImageSharpening.LEGACY,
                prefs.getInt("image_deband",0),prefs.getInt("image_cleanup",0),prefs.getInt("image_chroma",0),
                prefs.getInt("image_antialias",0),prefs.getInt("image_temporal",0),prefs.getInt("image_neural",0),
                prefs.getBoolean("image_auto_protect",true)).bounded(),
            PresentationPacing.entries.firstOrNull { it.name==prefs.getString("presentation_pacing",null) } ?: PresentationPacing.OFF,
            ReconstructionSettings(
                ReconstructionQuality.entries.firstOrNull { it.name==prefs.getString("reconstruction_quality",null) } ?: ReconstructionQuality.AUTO,
                prefs.getInt("reconstruction_strength",defaults.reconstruction.strength)).bounded(),
            audioMode = AudioPlaybackMode.entries.firstOrNull { it.name==prefs.getString("audio_mode",null) } ?: AudioPlaybackMode.STANDARD,
            imageLab = ImageLabSettings(prefs.getBoolean("image_lab_enabled",false),prefs.getInt("image_lab_restoration",35),
                HudProtection.entries.firstOrNull { it.name==prefs.getString("image_lab_protection",null) } ?: HudProtection.AUTO,
                ImageSupersampling.entries.firstOrNull { it.name==prefs.getString("image_lab_supersampling",null) } ?: ImageSupersampling.OFF,
                ExperimentalFrameGeneration.entries.firstOrNull { it.name==prefs.getString("image_lab_frame_generation",null) } ?: ExperimentalFrameGeneration.OFF,
                ImageComparison.entries.firstOrNull { it.name==prefs.getString("image_lab_comparison",null) } ?: ImageComparison.PROCESSED,prefs.getBoolean("image_lab_neural",false)).bounded(),
            imageComparison=ImageComparison.entries.firstOrNull { it.name==prefs.getString("image_comparison_all",null) } ?: ImageComparison.PROCESSED,
            gyroSource=GyroSource.entries.firstOrNull { it.name==prefs.getString("gyro_source",null) } ?: GyroSource.DEVICE,
            matchDisplayMode=prefs.getBoolean("match_display_mode",true),
            tryAlternateDecoder=prefs.getBoolean("try_alternate_decoder",false),
            tryMotionFence=prefs.getBoolean("try_motion_fence",false),
            tryIdleGamepadCadence=prefs.getBoolean("try_idle_gamepad_cadence",false),
            trySkipAudioSamples=prefs.getBoolean("try_skip_audio_samples",false))
    }
    fun save(settings: PlaybackSettings) = synchronized(writes) { write(values(settings)) }

    /** Apply the fields changed by this editor, even if other screens updated the saved settings. */
    fun update(base: PlaybackSettings, edited: PlaybackSettings): PlaybackSettings = synchronized(writes) {
        val before = values(base)
        val changes = values(edited).filter { (key, value) -> value != before[key] }
        if (changes.isNotEmpty()) {
            val firstWrite = prefs.all.keys.all { it == "game_hud_migrated" }
            write(if (firstWrite) values(read()) + changes else changes)
        }
        read()
    }

    private fun write(fields: Map<String, Any>) {
        val editor = prefs.edit().putBoolean(FSR_BASE_PROFILE_MIGRATED,true)
        fields.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()
    }

    private fun values(settings: PlaybackSettings): Map<String, Any> = mapOf(
        "video_mode" to settings.videoMode.name,
        "low_latency_video" to settings.videoMode.lowLatency,
        "resolution" to settings.resolution.name,
        "native_upscaling" to settings.nativeUpscaling,
        "match_display_mode" to settings.matchDisplayMode,
        "try_alternate_decoder" to settings.tryAlternateDecoder,
        "try_motion_fence" to settings.tryMotionFence,
        "try_idle_gamepad_cadence" to settings.tryIdleGamepadCadence,
        "try_skip_audio_samples" to settings.trySkipAudioSamples,
        "aspect" to settings.aspect.name,
        "local_coop" to settings.localCoOp,
        "rumble" to settings.rumble,
        "touch_mode" to settings.touchMode.name,
        "sharpness" to settings.sharpness.coerceIn(0,100),
        "saturation" to settings.saturation.coerceIn(0,200),
        "session_report" to settings.sessionReport,
        "video_codec" to settings.videoCodec.name,
        "fsr_mode" to settings.fsr.name,
        "fsr_sharpness" to settings.fsrSharpness.coerceIn(0,100),
        "brightness" to settings.brightness.coerceIn(-50,50),
        "contrast" to settings.contrast.coerceIn(50,150),
        "ip_preference" to settings.ipPreference.name,
        "desktop_input" to settings.desktopInput.name,
        "mouse_sensitivity" to settings.mouseSensitivity.coerceIn(25,300),
        "gyro_mode" to settings.gyroMode.name,
        "gyro_sensitivity" to settings.gyroSensitivity.coerceIn(25,300),
        "microphone" to settings.microphone,
        "audio_gain" to settings.audioGain.coerceIn(0,300),
        "touch_rumble" to settings.touchRumble,
        "gyro_native_touch" to settings.gyroNativeTouch,
        "fsr_output" to settings.fsrOutput.name,
        "image_sharpening" to settings.imageTreatment.sharpening.name,
        "image_deband" to settings.imageTreatment.deband.coerceIn(0,100),
        "image_cleanup" to settings.imageTreatment.cleanup.coerceIn(0,100),
        "image_chroma" to settings.imageTreatment.chroma.coerceIn(0,100),
        "image_antialias" to settings.imageTreatment.antialias.coerceIn(0,100),
        "image_temporal" to settings.imageTreatment.temporal.coerceIn(0,50),
        "image_neural" to settings.imageTreatment.neural.coerceIn(0,100),
        "image_auto_protect" to settings.imageTreatment.autoProtect,
        "presentation_pacing" to settings.presentationPacing.name,
        "reconstruction_quality" to settings.reconstruction.quality.name,
        "reconstruction_strength" to settings.reconstruction.strength.coerceIn(0,100),
        "audio_mode" to settings.audioMode.name,
        "image_lab_enabled" to settings.imageLab.enabled,
        "image_lab_restoration" to settings.imageLab.restoration.coerceIn(0,100),
        "image_lab_protection" to settings.imageLab.protection.name,
        "image_lab_supersampling" to settings.imageLab.supersampling.name,
        "image_lab_frame_generation" to settings.imageLab.frameGeneration.name,
        "image_lab_comparison" to settings.imageLab.comparison.name,
        "image_lab_neural" to settings.imageLab.neural,
        "image_comparison_all" to settings.imageComparison.name,
        "gyro_source" to settings.gyroSource.name
    )

    private companion object {
        const val FSR_BASE_PROFILE_MIGRATED = "fsr_base_profile_migrated"
        val writes = Any()
    }
}

/** Every new game starts hidden. Only an explicit controller/keyboard action changes visibility. */
class StatisticsVisibility {
    @Volatile var mode = 0
        private set
    fun beginSession() { mode = 0 }
    fun cycle(): Int { mode = (mode+1)%3; return mode }
    fun toggle(detailed: Boolean=false): Int { mode=if(mode==0) { if(detailed) 2 else 1 } else 0;return mode }
    fun configure(detailed: Boolean) { if(mode!=0) mode=if(detailed) 2 else 1 }
}
