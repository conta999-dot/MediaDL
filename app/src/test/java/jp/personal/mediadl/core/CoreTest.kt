package jp.personal.mediadl.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UrlDetectorTest {
    @Test fun detectsServices() {
        assertEquals(ServiceType.YOUTUBE, UrlDetector.detect("https://www.youtube.com/watch?v=abc"))
        assertEquals(ServiceType.YOUTUBE, UrlDetector.detect("https://youtu.be/abc"))
        assertEquals(ServiceType.YOUTUBE, UrlDetector.detect("https://m.youtube.com/shorts/abc"))
        assertEquals(ServiceType.X, UrlDetector.detect("https://x.com/user/status/123"))
        assertEquals(ServiceType.X, UrlDetector.detect("https://mobile.twitter.com/user/status/123"))
        assertEquals(ServiceType.INSTAGRAM, UrlDetector.detect("https://www.instagram.com/p/XYZ/"))
        assertEquals(ServiceType.FACEBOOK, UrlDetector.detect("https://fb.watch/abc/"))
        assertEquals(ServiceType.FACEBOOK, UrlDetector.detect("https://m.facebook.com/watch/?v=1"))
        assertEquals(ServiceType.TIKTOK, UrlDetector.detect("https://vm.tiktok.com/ZM123/"))
        assertEquals(ServiceType.UNKNOWN, UrlDetector.detect("https://example.com/x.com"))
        assertEquals(ServiceType.UNKNOWN, UrlDetector.detect("https://notyoutube.com/watch"))
        assertEquals(ServiceType.UNKNOWN, UrlDetector.detect("not a url"))
    }

    @Test fun extractsUrlFromSharedText() {
        assertEquals("https://x.com/a/status/1", UrlDetector.extractUrl("見て！ https://x.com/a/status/1 。"))
        assertEquals("https://youtu.be/abc", UrlDetector.extractUrl("動画「https://youtu.be/abc」"))
        assertEquals("https://www.instagram.com/p/XYZ/", UrlDetector.extractUrl("www.instagram.com/p/XYZ/"))
        assertNull(UrlDetector.extractUrl("URLなし"))
    }

    @Test fun xStatus() {
        assertEquals("user" to "123", UrlDetector.xStatus("https://x.com/user/status/123?s=20"))
    }
}

class DateFolderTest {
    @Test fun name() {
        assertEquals("DL_20261119", DateFolder.name(LocalDate.of(2026, 11, 19)))
        assertEquals("DL_20270105", DateFolder.name(LocalDate.of(2027, 1, 5)))
        assertTrue(Regex("DL_\\d{8}").matches(DateFolder.name()))
    }
}

class StorageCheckTest {
    private val G = StorageCheck.GIB
    @Test fun thresholdIsMaxOf1GbAnd10Percent() {
        assertEquals(G, StorageCheck.threshold(8 * G))
        assertEquals(12_800_000_000L, StorageCheck.threshold(128_000_000_000L))
    }
    @Test fun verdicts() {
        val total = 100 * G // threshold = 10GB
        assertTrue(StorageCheck.evaluate(1 * G, 2 * G, 50 * G, total) is StorageVerdict.Ok)
        assertTrue(StorageCheck.evaluate(5 * G, 5 * G, 12 * G, total) is StorageVerdict.Tight)
        assertTrue(StorageCheck.evaluate(5 * G, 10 * G, 8 * G, total) is StorageVerdict.Block)  // 一時領域込みで不足
        assertTrue(StorageCheck.evaluate(9 * G, 9 * G, 8 * G, total) is StorageVerdict.Block)
        assertTrue(StorageCheck.evaluate(0, 0, 50 * G, total) is StorageVerdict.Unknown)
    }
}

class FormatSelectorTest {
    private val fmts = listOf(
        Fmt("sb0", "mhtml", "none", "none", protocol = "mhtml"),
        Fmt("140", "m4a", "none", "mp4a.40.2", abr = 128.0, filesize = 4_000_000),
        Fmt("251", "webm", "none", "opus", abr = 140.0, filesize = 4_200_000),
        Fmt("160", "mp4", "avc1", "none", width = 256, height = 144, tbr = 100.0, filesize = 2_000_000),
        Fmt("134", "mp4", "avc1", "none", width = 640, height = 360, tbr = 400.0, filesize = 8_000_000),
        Fmt("136", "mp4", "avc1", "none", width = 1280, height = 720, tbr = 1500.0, filesize = 30_000_000),
        Fmt("137", "mp4", "avc1", "none", width = 1920, height = 1080, tbr = 3000.0, filesize = 60_000_000),
        Fmt("248", "webm", "vp9", "none", width = 1920, height = 1080, tbr = 2500.0, filesizeApprox = 50_000_000),
        Fmt("313", "webm", "vp9", "none", width = 3840, height = 2160, tbr = 12000.0, filesizeApprox = 300_000_000),
        Fmt("18", "mp4", "avc1", "mp4a", width = 640, height = 360, tbr = 500.0),
    )

    @Test fun bestIsSourceMaximum() {
        val s = FormatSelector.select(fmts, 200.0, Quality.BEST)
        assertTrue(s.formatSpec, s.formatSpec.startsWith("313+251/"))
        assertEquals(2160, s.height)
        assertEquals(304_200_000L, s.estimatedBytes)
        assertEquals("最良（元の最大画質）", s.label)
    }
    @Test fun normalIs1080p() {
        val s = FormatSelector.select(fmts, 200.0, Quality.NORMAL)
        assertTrue(s.formatSpec, s.formatSpec.startsWith("137+140/"))   // 同じ1080pなら mp4 系を優先
        assertEquals(64_000_000L, s.estimatedBytes)
        assertEquals("普通（1080p）", s.label)
        assertTrue(s.formatSpec.endsWith("bv*[height<=1080]+ba/b[height<=1080]/wv*+ba/w"))
    }
    @Test fun lowIs720p() {
        val s = FormatSelector.select(fmts, 200.0, Quality.LOW)
        assertTrue(s.formatSpec, s.formatSpec.startsWith("136+140/"))
        assertEquals(34_000_000L, s.estimatedBytes)
        assertEquals("低画質（720p）", s.label)
    }
    @Test fun normalFallsBackToNearestLower() {
        val no1080 = fmts.filter { it.p != 1080 && it.p != 2160 }
        val s = FormatSelector.select(no1080, 200.0, Quality.NORMAL)
        assertEquals(720, s.height)
        assertEquals("普通（1080pなし→720p）", s.label)
    }
    @Test fun lowAboveTargetUsesNearestHigher() {
        val only = listOf(Fmt("uhd", "mp4", "avc1", "mp4a", width = 3840, height = 2160, tbr = 9000.0),
            Fmt("fhd", "mp4", "avc1", "mp4a", width = 1920, height = 1080, tbr = 3000.0))
        val s = FormatSelector.select(only, 10.0, Quality.LOW)
        assertTrue(s.formatSpec.startsWith("fhd/"))
        assertEquals("低画質（720pなし→1080p）", s.label)
    }
    @Test fun verticalVideoUsesShortSide() {
        val shorts = listOf(
            Fmt("v1080", "mp4", "avc1", "none", width = 1080, height = 1920, tbr = 3000.0),
            Fmt("v720", "mp4", "avc1", "none", width = 720, height = 1280, tbr = 1500.0),
            Fmt("a", "m4a", "none", "mp4a", abr = 128.0),
        )
        assertTrue(FormatSelector.select(shorts, 30.0, Quality.NORMAL).formatSpec.startsWith("v1080+a/"))
        assertTrue(FormatSelector.select(shorts, 30.0, Quality.LOW).formatSpec.startsWith("v720+a/"))
    }
    @Test fun sizeFromBitrateWhenNoFilesize() {
        val s = FormatSelector.select(listOf(Fmt("x", "mp4", "avc1", "mp4a", width = 1280, height = 720, tbr = 800.0)), 100.0, Quality.LOW)
        assertEquals(10_000_000L, s.estimatedBytes) // 800kbps * 100s / 8
    }
    @Test fun noFormats() {
        val s = FormatSelector.select(emptyList(), 0.0, Quality.BEST, infoSize = 1234)
        assertEquals(1234L, s.estimatedBytes)
        assertEquals("bv*+ba/b", s.formatSpec)
    }
}
