package org.aaaasta03.astrabrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSourceTest {
    @Test fun recognizesCommonDirectVideoUrls() {
        assertTrue(VideoSource.isDirectMediaUrl("https://cdn.example/video.mp4"))
        assertTrue(VideoSource.isDirectMediaUrl("https://cdn.example/clip.webm?token=abc"))
        assertTrue(VideoSource.isDirectMediaUrl("https://cdn.example/live.m3u8"))
        assertTrue(VideoSource.isDirectMediaUrl("https://cdn.example/stream.mpd"))
    }

    @Test fun doesNotTreatOrdinaryPagesAsMedia() {
        assertFalse(VideoSource.isDirectMediaUrl("https://www.youtube.com/watch?v=abc"))
        assertFalse(VideoSource.isDirectMediaUrl("https://example.com/video?id=12"))
        assertFalse(VideoSource.isDirectMediaUrl("file:///sdcard/video.mp4"))
    }
}
