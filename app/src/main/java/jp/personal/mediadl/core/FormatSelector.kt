package jp.personal.mediadl.core

/** yt-dlp の formats 1件分（必要な項目のみ）。 */
data class Fmt(
    val id: String,
    val ext: String = "",
    val vcodec: String? = null,
    val acodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val tbr: Double = 0.0,
    val abr: Double = 0.0,
    val filesize: Long = 0,
    val filesizeApprox: Long = 0,
    val protocol: String = "",
) {
    val isStoryboard get() = ext == "mhtml" || vcodec == "images" || protocol == "mhtml"
    val isAudioOnly get() = vcodec == "none" && acodec != null && acodec != "none"
    val hasVideo get() = !isStoryboard && vcodec != "none" && !(vcodec == null && height == 0 && ext in AUDIO_EXT)
    val isVideoOnly get() = hasVideo && acodec == "none"
    val isMp4ish get() = ext == "mp4" || ext == "m4a" || (vcodec?.startsWith("avc") == true)
    /** 画質の「p」値。縦長動画(1080x1920等)も正しく扱うため短辺を使う */
    val p get() = if (width > 0 && height > 0) minOf(width, height) else height

    fun size(durationSec: Double): Long = when {
        filesize > 0 -> filesize
        filesizeApprox > 0 -> filesizeApprox
        tbr > 0 && durationSec > 0 -> (tbr * 1000.0 / 8.0 * durationSec).toLong()
        else -> 0L
    }

    companion object { val AUDIO_EXT = setOf("m4a", "mp3", "aac", "opus", "ogg", "wav", "flac") }
}

enum class Quality(val label: String, val maxHeight: Int?, val desc: String) {
    BEST("最良", null, "元の最大画質"),
    NORMAL("普通", 1080, "1080p"),
    LOW("低画質", 720, "720p");

    /** 画面表示用ラベル。目標画質が無く別の画質になる場合は実際の画質を併記 */
    fun displayLabel(actualP: Int = 0): String {
        val max = maxHeight
        return if (max != null && actualP > 0 && actualP != max) "$label（${max}pなし→${actualP}p）" else "$label（$desc）"
    }
}

data class Selection(
    val quality: Quality,
    /** yt-dlp の -f に渡す指定（選択IDの後に汎用フォールバックを連結） */
    val formatSpec: String,
    /** 想定サイズ（0=不明） */
    val estimatedBytes: Long,
    val height: Int,
    val summary: String,
) {
    /** 「普通（1080pなし→720p）」のような実際の画質つきラベル */
    val label: String get() = quality.displayLabel(height)
}

object FormatSelector {

    private fun genericSpec(q: Quality): String = when (q) {
        Quality.BEST -> "bv*+ba/b"
        Quality.NORMAL -> "bv*[height<=1080]+ba/b[height<=1080]/wv*+ba/w"
        Quality.LOW -> "bv*[height<=720]+ba/b[height<=720]/wv*+ba/w"
    }

    fun select(formats: List<Fmt>, durationSec: Double, q: Quality, infoSize: Long = 0): Selection {
        val videos = formats.filter { it.hasVideo }
        if (videos.isEmpty()) {
            return Selection(q, genericSpec(q), infoSize.coerceAtLeast(0), 0, "形式情報なし")
        }
        // 目標画質以下で最も高い画質（同画質なら mp4 系 → ビットレート優先）。
        // 目標以下が無い場合は、それより上で最も近い（最小の）画質にする。
        val cmp = compareBy<Fmt>({ it.p }, { if (it.isMp4ish) 1 else 0 }, { it.tbr })
        val chosen: Fmt = when (val max = q.maxHeight) {
            null -> videos.maxWith(cmp)
            else -> videos.filter { it.p in 1..max }.maxWithOrNull(cmp)
                ?: videos.minWith(compareBy<Fmt>({ it.p }, { -it.tbr }))
        }
        var spec = chosen.id
        var size = chosen.size(durationSec)
        var audioDesc = ""
        if (chosen.isVideoOnly) {
            val audios = formats.filter { it.isAudioOnly }
            val audio = audios.maxWithOrNull(compareBy<Fmt>({ if (it.isMp4ish == chosen.isMp4ish) 1 else 0 }, { it.abr.takeIf { a -> a > 0 } ?: it.tbr }))
            if (audio != null) {
                spec = "${chosen.id}+${audio.id}"
                val a = audio.size(durationSec)
                size = if (size > 0 && a > 0) size + a else if (size > 0) size else 0
                audioDesc = " + 音声"
            }
        }
        val res = if (chosen.p > 0) "${chosen.p}p" else "解像度不明"
        return Selection(q, "$spec/${genericSpec(q)}", size, chosen.p, "$res (${chosen.ext}$audioDesc)")
    }
}
