package com.xcloud.tclnative.settings

import com.xcloud.tclnative.i18n.UiText

/** Everyday choices describe one processing chain; advanced preferences remain readable as Custom. */
enum class ImageProfile(private val source: String) {
    ORIGINAL("Imagem original"), LIGHT("Perfil leve"), FSR("AMD FSR 1.0.2"), CUSTOM("Personalizado");
    val label get()=UiText.text(source)
}

object ImageProfiles {
    fun label(value: PlaybackSettings): String = selected(value).let { profile ->
        if(profile==ImageProfile.CUSTOM && value.fsr==FsrMode.FSR1) UiText.join(profile.label," • ",value.fsr.label)
        else profile.label
    }

    fun selected(value: PlaybackSettings): ImageProfile {
        if(value.imageLab.enabled)return ImageProfile.CUSTOM
        val filters=value.imageTreatment
        if(value.fsr==FsrMode.OFF && value.sharpness==0 && !filters.active)return ImageProfile.ORIGINAL
        if(value.fsr==FsrMode.OFF && filters.sharpening==ImageSharpening.RCAS_LUMA && !filters.spatial &&
            filters.temporal==0 && filters.neural==0)return ImageProfile.LIGHT
        if(isFsrProfileBase(value) && value.reconstruction.strength==0 && filters.temporal==0)
            return ImageProfile.FSR
        return ImageProfile.CUSTOM
    }

    private fun isFsrProfileBase(value: PlaybackSettings): Boolean {
        val filters=value.imageTreatment
        return value.fsr==FsrMode.FSR1 && value.fsrOutput==FsrOutput.AUTO &&
            value.reconstruction.quality==ReconstructionQuality.AUTO && !filters.spatial && filters.neural==0 &&
            filters.sharpening==ImageSharpening.RCAS_LUMA && !value.imageLab.enabled
    }
    fun apply(value: PlaybackSettings,profile: ImageProfile): PlaybackSettings {
        if(profile==ImageProfile.CUSTOM)return value
        val base=value.copy(nativeUpscaling=true,fsr=FsrMode.OFF,fsrOutput=FsrOutput.AUTO,sharpness=0,
            imageTreatment=ImageTreatmentSettings(),
            reconstruction=ReconstructionSettings(),imageLab=value.imageLab.copy(enabled=false),
            imageComparison=ImageComparison.PROCESSED)
        return when(profile) {
            ImageProfile.LIGHT -> base.copy(sharpness=25,imageTreatment=base.imageTreatment.copy(sharpening=ImageSharpening.RCAS_LUMA))
            ImageProfile.FSR -> base.copy(fsr=FsrMode.FSR1,fsrSharpness=50,
                imageTreatment=base.imageTreatment.copy(sharpening=ImageSharpening.RCAS_LUMA))
            else -> base
        }
    }
    fun intensity(value: PlaybackSettings)=when(selected(value)) {
        ImageProfile.LIGHT -> value.sharpness
        ImageProfile.FSR -> value.fsrSharpness
        else -> 0
    }
    fun withIntensity(value: PlaybackSettings,amount: Int): PlaybackSettings {
        val level=amount.coerceIn(0,100)
        return when(selected(value)) {
            ImageProfile.LIGHT -> value.copy(sharpness=level)
            ImageProfile.FSR -> value.copy(fsrSharpness=level)
            else -> value
        }
    }

    internal fun migrateFsrPreset(value: PlaybackSettings): PlaybackSettings {
        val strength=value.reconstruction.strength
        val filters=value.imageTreatment
        return when {
            strength>0 && isFsrProfileBase(value) && filters.temporal==strength/2 && value.fsrSharpness==strength/2 ->
                value.copy(fsrSharpness=strength,reconstruction=ReconstructionSettings(),imageTreatment=filters.copy(temporal=0))
            // Older builds populated this strength even when the user never opened the detail controls.
            value.reconstruction==ReconstructionSettings(strength=50) && !value.imageLab.enabled &&
                !filters.spatial && filters.temporal==0 && filters.neural==0 -> value.copy(reconstruction=ReconstructionSettings())
            else -> value
        }
    }

    internal fun forRendering(value: PlaybackSettings): PlaybackSettings =
        if (value.fsrSharpness==0 && selected(value)==ImageProfile.FSR) {
            value.copy(fsr=FsrMode.OFF,imageTreatment=value.imageTreatment.copy(sharpening=ImageSharpening.LEGACY))
        } else value
}
