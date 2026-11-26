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
        if(value.fsr==FsrMode.FSR1 && value.fsrOutput==FsrOutput.AUTO &&
            value.reconstruction.quality==ReconstructionQuality.AUTO && !filters.spatial && filters.neural==0 &&
            filters.sharpening==ImageSharpening.RCAS_LUMA && filters.temporal==value.reconstruction.strength/2 &&
            value.fsrSharpness==value.reconstruction.strength/2)
            return ImageProfile.FSR
        return ImageProfile.CUSTOM
    }
    fun apply(value: PlaybackSettings,profile: ImageProfile): PlaybackSettings {
        if(profile==ImageProfile.CUSTOM)return value
        val base=value.copy(nativeUpscaling=true,fsr=FsrMode.OFF,fsrOutput=FsrOutput.AUTO,sharpness=0,
            imageTreatment=ImageTreatmentSettings(),
            reconstruction=ReconstructionSettings(),imageLab=value.imageLab.copy(enabled=false),
            imageComparison=ImageComparison.PROCESSED)
        return when(profile) {
            ImageProfile.LIGHT -> base.copy(sharpness=25,imageTreatment=base.imageTreatment.copy(sharpening=ImageSharpening.RCAS_LUMA))
            ImageProfile.FSR -> base.copy(fsr=FsrMode.FSR1,fsrSharpness=25,
                imageTreatment=base.imageTreatment.copy(temporal=25,sharpening=ImageSharpening.RCAS_LUMA))
            else -> base
        }
    }
    fun intensity(value: PlaybackSettings)=when(selected(value)) {
        ImageProfile.LIGHT -> value.sharpness
        ImageProfile.FSR -> value.reconstruction.strength
        else -> 0
    }
    fun withIntensity(value: PlaybackSettings,amount: Int): PlaybackSettings {
        val level=amount.coerceIn(0,100)
        return when(selected(value)) {
            ImageProfile.LIGHT -> value.copy(sharpness=level)
            ImageProfile.FSR -> value.copy(fsrSharpness=level/2,reconstruction=value.reconstruction.copy(strength=level),
                imageTreatment=value.imageTreatment.copy(temporal=level/2))
            else -> value
        }
    }
}
