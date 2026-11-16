package com.xcloud.tclnative

import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.*
import com.xcloud.tclnative.catalog.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CustomStatisticsTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Test fun defaultShowsOnlyTheThreeRequestedMeasurements() {
        UiText.apply(app,AppLanguage.PORTUGUESE)
        val text=CustomStatistics.format(StreamMetrics(width=1280,height=720,fps=59.0,rttMs=43.0,bitrateMbps=5.5,codec="H264 Main",decodeMs=11.0),InputMetrics(),StatisticsSettings())
        assertTrue(text.contains("FPS do vídeo: 59"));assertTrue(text.contains("Ping (RTT): 43.0 ms"));assertTrue(text.contains("Bitrate: 5.50 Mbps"))
        assertFalse(text.contains("1280"));assertFalse(text.contains("H264"));assertFalse(text.contains("decodificação"));assertFalse(text.contains("\n"))
    }
    @Test fun selectionUsesActualReceivedValuesAndUnknownCountersStayUnknown() {
        UiText.apply(app,AppLanguage.PORTUGUESE)
        val selection=StatisticsSettings(fields=setOf(StatisticField.RESOLUTION,StatisticField.CODEC,StatisticField.RTT))
        val text=CustomStatistics.format(StreamMetrics(width=1280,height=720,codec="H264 Baseline",rttMs=Double.NaN),InputMetrics(),selection)
        assertTrue(text.contains("1280×720"));assertTrue(text.contains("H264 Baseline"));assertTrue(text.contains("n/d ms"))
        assertFalse(text.contains("FPS"));assertFalse(text.contains("NaN"));assertFalse(text.contains("1920"))
    }
    @Test fun cloudTimeCanBeShownAloneAndPreservesUnlimitedLimitedAndUnavailableServerStates() {
        UiText.apply(app,AppLanguage.PORTUGUESE)
        val selected=StatisticsSettings(fields=setOf(StatisticField.PLAYTIME))
        for(value in listOf(CloudPlaytime(CloudPlaytimeStatus.UNLIMITED),CloudPlaytime(CloudPlaytimeStatus.LIMITED,45000),
            CloudPlaytime(CloudPlaytimeStatus.LIMITED,30),CloudPlaytime(failure=PlaytimeFailure.HTTP,httpStatus=400))) {
            val text=CustomStatistics.format(StreamMetrics(),InputMetrics(),selected,value)
            assertEquals(value.label,text);assertFalse(text.contains("HTTP"));assertFalse(text.contains("FPS"))
        }
    }
    @Test fun cloudTimeHasItsOwnLineAndRemainsAbsentFromTheDefaultThreeMeasurements() {
        UiText.apply(app,AppLanguage.PORTUGUESE)
        val quota=CloudPlaytime(CloudPlaytimeStatus.UNLIMITED)
        val metrics=StreamMetrics(fps=60.0,rttMs=40.0,bitrateMbps=10.0)
        val plain=CustomStatistics.format(metrics,InputMetrics(),StatisticsSettings(),quota)
        assertFalse(plain.contains("Tempo restante"));assertFalse(plain.contains("\n"))
        val selected=StatisticsSettings(fields=StatisticsSettings.DEFAULT_FIELDS+StatisticField.PLAYTIME)
        val text=CustomStatistics.format(metrics,InputMetrics(),selected,quota)
        assertEquals(quota.label,text.lineSequence().first());assertEquals(2,text.lineSequence().count())
        assertTrue(text.contains("FPS do vídeo: 60"));assertTrue(text.contains("Ping (RTT): 40.0 ms"))
        StatisticsSettingsStore(app).save(selected)
        assertTrue(StatisticField.PLAYTIME in StatisticsSettingsStore(app).read().fields)
    }
    @Test fun savedLayoutSurvivesRestartAndDoesNotChangeVideoOrControllerPreferences() {
        val prefs=app.getSharedPreferences("stream_statistics",0);prefs.edit().clear().commit()
        val before=PlaybackSettingsStore(app).read()
        val selected=StatisticsSettings(setOf(StatisticField.CODEC,StatisticField.FPS),true,StatisticsPosition.BOTTOM_RIGHT,18,30)
        StatisticsSettingsStore(app).save(selected)
        assertEquals(selected,StatisticsSettingsStore(app).read());assertEquals(before,PlaybackSettingsStore(app).read())
        prefs.edit().putString("layout","""{"fields":["unknown"],"position":"bad","size":999,"background":-5}""").commit()
        val recovered=StatisticsSettingsStore(app).read()
        assertEquals(StatisticsSettings.DEFAULT_FIELDS,recovered.fields);assertEquals(StatisticsPosition.TOP_LEFT,recovered.position)
        assertEquals(24,recovered.textSize);assertEquals(0,recovered.background)
    }
    @Test fun visibilityIsATrueToggleAndEveryGameStartsHiddenWithItsSavedLayout() {
        val state=StatisticsVisibility()
        assertEquals(1,state.toggle());assertEquals(0,state.toggle())
        assertEquals(2,state.toggle(true));state.configure(false);assertEquals(1,state.mode)
        state.beginSession();assertEquals(0,state.mode);state.configure(true);assertEquals(0,state.mode)
    }
}
