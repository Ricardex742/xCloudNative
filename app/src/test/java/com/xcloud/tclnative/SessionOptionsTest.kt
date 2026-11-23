package com.xcloud.tclnative

import android.content.Context
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamProfile
import com.xcloud.tclnative.webrtc.StreamResolution
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SessionOptionsTest {
    @Test fun verifiedAccountRetainsLegacySettingsAndExistingOverridesAcrossLogins() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = SessionOptionsStore(context, "account-hash")
        legacy.set(null, SessionOptionField.LOCALE, "zh-TW")
        legacy.set("game", SessionOptionField.BITRATE, 6000)
        SessionOptionsStore(context, "xuid:12345").set("game", SessionOptionField.REGION, "JapanEast")

        val verified = SessionOptionsStore(context, "xuid:12345", "account-hash")
        val expected = SessionOptions(6000, "zh-TW", "JapanEast")
        assertEquals(expected, verified.read("game"))
        assertEquals(expected, legacy.read("game"))
        assertEquals(SessionOptions(), SessionOptionsStore(context, "xuid:67890", "account-hash").read("game"))
        verified.inherit("game", SessionOptionField.BITRATE)
        assertEquals(expected.copy(maxBitrateKbps = 0), SessionOptionsStore(context, "account-hash").read("game"))
        assertEquals(expected.copy(maxBitrateKbps = 0), SessionOptionsStore(context, "xuid:12345", "account-hash").read("game"))
    }

    @Test fun changingBitrateDoesNotPinTheInheritedLanguageOrRegion() {
        val context=RuntimeEnvironment.getApplication()
        val store=SessionOptionsStore(context,"field-inheritance")
        store.save(null,SessionOptions(locale="en-US",region="BrazilSouth"))
        store.set("game",SessionOptionField.BITRATE,5000)
        store.set(null,SessionOptionField.LOCALE,"zh-CN")
        store.set(null,SessionOptionField.REGION,"EastUS")
        val reopened=SessionOptionsStore(context,"field-inheritance")
        assertEquals(SessionOptions(5000,"zh-CN","EastUS"),reopened.read("game"))
        assertFalse(reopened.hasOverride("game",SessionOptionField.LOCALE))
        assertTrue(reopened.hasOverride("game",SessionOptionField.BITRATE))
        assertEquals(SessionOptions(),SessionOptionsStore(context,"other-fields-account").read("game"))
    }
    @Test fun legacySnapshotsRemainExplicitAndEachFieldCanBeReturnedToInheritance() {
        val store=SessionOptionsStore(RuntimeEnvironment.getApplication(),"legacy-fields")
        val original=SessionOptions(12000,"es-MX","BrazilSouth")
        store.save("game",original)
        store.set(null,SessionOptionField.LOCALE,"zh-CN")
        store.set("game",SessionOptionField.BITRATE,8000)
        assertEquals(original.copy(maxBitrateKbps=8000),store.read("game"))
        store.inherit("game",SessionOptionField.LOCALE)
        assertEquals(original.copy(maxBitrateKbps=8000,locale="zh-CN"),store.read("game"))
        store.inherit("game",SessionOptionField.REGION)
        store.inherit("game",SessionOptionField.BITRATE)
        assertFalse(store.hasOverride("game"))
        assertEquals(store.read(),store.read("game"))
    }
    @Test fun simplifiedChinesePersistsIndependentlyOfUiAndOtherSessionEdits() {
        val context=RuntimeEnvironment.getApplication()
        val store=SessionOptionsStore(context,"simplified-game")
        store.set("game",SessionOptionField.LOCALE,"zh-CN")
        store.set("game",SessionOptionField.BITRATE,15000)
        store.set("game",SessionOptionField.REGION,"BrazilSouth")
        com.xcloud.tclnative.i18n.AppLanguageStore(context).save(com.xcloud.tclnative.i18n.AppLanguage.ENGLISH)
        assertEquals(SessionOptions(15000,"zh-CN","BrazilSouth"),SessionOptionsStore(context,"simplified-game").read("game"))
        assertEquals("pt-BR",store.read().locale)
        assertEquals("zh-CN",SessionOptions(locale="zh-CN").normalized().locale)
    }
    @Test fun gameOverridesPersistAreAccountScopedAndResetToTheCurrentDefault() {
        val context=RuntimeEnvironment.getApplication()
        val first=SessionOptionsStore(context,"account-one")
        val defaults=SessionOptions(12000,"en-US","BrazilSouth")
        first.save(null,defaults)
        assertEquals(defaults,first.read("game-one"))
        val game=SessionOptions(3000,"es-MX","EastUS")
        first.save("game-one",game)
        first.save(null,defaults.copy(maxBitrateKbps=30000))
        assertEquals(game,SessionOptionsStore(context,"account-one").read("game-one"))
        assertEquals(SessionOptions(),SessionOptionsStore(context,"account-two").read("game-one"))
        first.reset("game-one")
        assertFalse(first.hasOverride("game-one"))
        assertEquals(defaults.copy(maxBitrateKbps=30000),first.read("game-one"))
    }
    @Test fun newPreferencesPersistWithOptionalFeaturesOffByDefaultAndBoundsApplied() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("stream_preferences",Context.MODE_PRIVATE).edit().clear().commit()
        val store=PlaybackSettingsStore(context)
        assertEquals(PlaybackSettings(),store.read())
        val selected=PlaybackSettings(brightness=25,contrast=125,ipPreference=IpPreference.IPV6,
            desktopInput=DesktopInputMode.NATIVE,mouseSensitivity=150,gyroMode=GyroMode.LT,gyroSensitivity=75,
            microphone=true,audioGain=175,touchRumble=true,gyroNativeTouch=true)
        store.save(selected)
        assertEquals(selected,PlaybackSettingsStore(context).read())
        store.save(selected.copy(brightness=500,contrast=999,audioGain=1000,mouseSensitivity=-1,gyroSensitivity=500))
        assertEquals(selected.copy(brightness=50,contrast=150,audioGain=300,mouseSensitivity=25,gyroSensitivity=300),store.read())
    }
    @Test fun codecBudgetCanBeLowerOrHigherAtAnyResolutionWithoutRewritingTheProfile() {
        val sdp="v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 profile-level-id=42e01f;packetization-mode=1\r\n"
        for(resolution in StreamResolution.entries) for(cap in listOf(1000,6000,50000)) {
            val output=StreamProfile.receiveSdp(sdp,resolution,false,cap)
            assertTrue(output.contains("b=AS:$cap\r\n"))
            assertTrue(output.contains("x-google-max-bitrate=$cap"))
            assertTrue(output.contains("profile-level-id=42e01f;packetization-mode=1"))
            val min=Regex("x-google-min-bitrate=([0-9]+)").find(output)!!.groupValues[1].toInt()
            assertTrue(min<=cap)
            assertEquals(output,StreamProfile.receiveSdp(output,resolution,false,cap))
        }
        assertTrue(StreamProfile.receiveSdp(sdp,StreamResolution.HD_720,true,0).contains("b=AS:6000"))
    }
}
