package jp.personal.mediadl.core

import java.net.URI

/** 対応サービス。color はバッジ表示用の ARGB。 */
enum class ServiceType(val label: String, val color: Long, val glyph: String) {
    YOUTUBE("YouTube", 0xFFFF0033, "▶"),
    X("X (Twitter)", 0xFF1D9BF0, "X"),
    INSTAGRAM("Instagram", 0xFFE1306C, "IG"),
    FACEBOOK("Facebook", 0xFF1877F2, "f"),
    TIKTOK("TikTok", 0xFFFE2C55, "♪"),
    UNKNOWN("未対応/不明", 0xFF757575, "?");
}

object UrlDetector {
    private val urlRegex = Regex("""https?://[^\s<>"'「」『』（）]+""", RegexOption.IGNORE_CASE)
    private val bareRegex = Regex(
        """(?:^|\s)((?:www\.|m\.|mobile\.|vm\.|vt\.)?(?:youtube\.com|youtu\.be|x\.com|twitter\.com|instagram\.com|facebook\.com|fb\.watch|tiktok\.com)/[^\s<>"']*)""",
        RegexOption.IGNORE_CASE
    )
    private const val TRAILING = ".,;:!?)]}>。、！？）】」』"

    /** 共有テキスト等から最初の URL を取り出す（スキーム無しの既知ドメインも補完）。 */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = urlRegex.find(text)?.value ?: bareRegex.find(text)?.groupValues?.get(1)?.let { "https://$it" }
        return m?.trimEnd { it in TRAILING }?.takeIf { it.length > 10 }
    }

    fun host(url: String): String? = try {
        URI(url.trim()).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    fun detect(url: String?): ServiceType {
        if (url.isNullOrBlank()) return ServiceType.UNKNOWN
        val h = host(url) ?: return ServiceType.UNKNOWN
        fun match(vararg domains: String) = domains.any { h == it || h.endsWith(".$it") }
        return when {
            match("youtube.com", "youtu.be", "youtube-nocookie.com") -> ServiceType.YOUTUBE
            match("x.com", "twitter.com", "fxtwitter.com", "vxtwitter.com", "fixupx.com") -> ServiceType.X
            match("instagram.com", "instagr.am") -> ServiceType.INSTAGRAM
            match("facebook.com", "fb.watch", "fb.com") -> ServiceType.FACEBOOK
            match("tiktok.com") -> ServiceType.TIKTOK
            else -> ServiceType.UNKNOWN
        }
    }

    /** X の投稿 URL から (ユーザー名, ステータスID) を取り出す。 */
    fun xStatus(url: String): Pair<String, String>? {
        val m = Regex("""(?:x|twitter|fxtwitter|vxtwitter|fixupx)\.com/([^/?#]+)/status(?:es)?/(\d+)""", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }
}
