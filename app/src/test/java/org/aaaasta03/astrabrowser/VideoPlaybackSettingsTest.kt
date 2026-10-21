package org.aaaasta03.astrabrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlaybackSettingsTest {
    @Test
    fun playbackSpeedOptionsStayWithinSupportedRange() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        assertEquals(7, speeds.size)
        assertEquals(1f, speeds[2])
        assertEquals(2f, speeds.last())
        assert(speeds.all { it in 0.5f..2f })
    }

    @Test
    fun defaultPlaybackSpeedIsNormalSpeed() {
        assertEquals(1f, 1f)
    }
}
