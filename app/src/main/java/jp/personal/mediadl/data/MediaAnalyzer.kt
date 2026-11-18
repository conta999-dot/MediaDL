package jp.personal.mediadl.data

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import jp.personal.mediadl.Engine
import jp.personal.mediadl.core.Fmt
import jp.personal.mediadl.core.ServiceType
import jp.personal.mediadl.core.UrlDetector
import org.json.JSONArray
import org.json.JSONObject

/**
 * URL を解析して、ダウンロード対象（動画・画像）の一覧を作る。
 * 1) yt-dlp -J（JSON ダンプ）  2) X は vxtwitter API で画像を補完  3) og:image での画像抽出
 * ※ 実際のダウンロードは行わない（メタデータ取得のみ）。
 */
class MediaAnalyzer(private val settings: Settings) {

    suspend fun analyze(url: String): Analysis {
        Engine.await()
        val service = UrlDetector.detect(url)
        val notes = mutableListOf<String>()
        val items = mutableListOf<MediaItem>()
        var title = ""
        var ytdlpError: String? = null

        try {
            val root = dumpJson(url, service)
            title = root.optString("title").ifBlank { root.optString("id") }
            collect(root, null, items)
        } catch (e: Exception) {
            ytdlpError = (e.message ?: e.toString()).lineSequence()
                .firstOrNull { it.contains("ERROR") } ?: e.message?.take(300)
        }

        // X: 画像は yt-dlp が扱わない（写真のみの投稿はエラーになる）ため vxtwitter API で補完
        if (service == ServiceType.X) {
            runCatching { xImages(url) }.getOrNull()?.let { imgs ->
                val known = items.filterIsInstance<MediaItem.Image>().map { it.url }.toSet()
                imgs.filter { it.url !in known }.forEach { items += it }
                if (imgs.isNotEmpty()) notes += "画像はvxtwitter公開APIで取得しました"
            }
        }

        // 何も取れない場合は og:image（ページのプレビュー画像）を探す
        if (items.isEmpty()) {
            val og = runCatching { ogImages(url) }.getOrDefault(emptyList())
            items += og
            if (og.isNotEmpty()) notes += "ページのプレビュー画像(og:image)を取得しました。原寸でない場合があります"
        }

        if (items.isEmpty()) {
            val hint = when (service) {
                ServiceType.INSTAGRAM, ServiceType.FACEBOOK, ServiceType.X ->
                    "\nこのサービスはログインが必要な場合があります。設定から cookies.txt を取り込んでください。"
                ServiceType.YOUTUBE -> "\n設定の「yt-dlpを更新」を試してください。"
                else -> ""
            }
            throw IllegalStateException((ytdlpError ?: "メディアが見つかりませんでした") + hint)
        }

        // 画像サイズを HEAD で取得（最大40件）
        val sized = items.mapIndexed { i, it ->
            if (it is MediaItem.Image && it.size == 0L && i < 40) it.copy(size = Http.contentLength(it.url)) else it
        }
        if (title.isBlank()) title = "${service.label} のメディア"
        return Analysis(url, service, title, sized, notes)
    }

    private fun dumpJson(url: String, service: ServiceType): JSONObject {
        val req = YoutubeDLRequest(url)
            .addOption("-J")
            .addOption("--no-warnings")
            .addOption("--ignore-no-formats-error")
        if (service == ServiceType.YOUTUBE) req.addOption("--no-playlist")
        settings.activeCookies()?.let { req.addOption("--cookies", it.absolutePath) }
        val resp = YoutubeDL.getInstance().execute(req)
        val out = resp.out.trim()
        val start = out.indexOf('{')
        if (start < 0) throw IllegalStateException(resp.err.ifBlank { "yt-dlp の出力が空です" })
        return JSONObject(out.substring(start))
    }

    private fun collect(obj: JSONObject, index: Int?, out: MutableList<MediaItem>) {
        val entries = obj.optJSONArray("entries")
        if (entries != null && entries.length() > 0) {
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val idx = e.optInt("playlist_index", i + 1)
                collect(e, idx, out)
            }
            return
        }
        val title = obj.optString("title").ifBlank { obj.optString("id", "media") }
        val id = obj.optString("id", "media")
        val formats = parseFormats(obj.optJSONArray("formats"))
        val ext = obj.optString("ext").lowercase()
        val directUrl = obj.optString("url")

        if (ext in IMAGE_EXT && directUrl.startsWith("http")) {
            out += MediaItem.Image(title, directUrl, ext, 0); return
        }
        val hasVideo = formats.any { it.hasVideo } ||
            (formats.isEmpty() && directUrl.startsWith("http") && ext !in IMAGE_EXT && ext.isNotBlank())
        if (hasVideo) {
            out += MediaItem.Video(
                title = title, id = id, playlistIndex = index,
                durationSec = obj.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
                formats = formats,
                infoSize = obj.optLong("filesize", 0).takeIf { it > 0 } ?: obj.optLong("filesize_approx", 0),
            )
            return
        }
        // 動画形式が無い = 画像投稿。最大サイズのサムネイル（=元画像）を採用
        bestThumbnail(obj.optJSONArray("thumbnails"))?.let { u ->
            out += MediaItem.Image(title, u, guessExt(u), 0)
        } ?: obj.optString("thumbnail").takeIf { it.startsWith("http") }?.let { u ->
            out += MediaItem.Image(title, u, guessExt(u), 0)
        }
    }

    private fun parseFormats(arr: JSONArray?): List<Fmt> {
        if (arr == null) return emptyList()
        val list = ArrayList<Fmt>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            fun s(k: String) = if (f.isNull(k)) null else f.optString(k)
            fun d(k: String) = if (f.isNull(k)) 0.0 else f.optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }
            list += Fmt(
                id = f.optString("format_id"),
                ext = f.optString("ext"),
                vcodec = s("vcodec"),
                acodec = s("acodec"),
                width = d("width").toInt(),
                height = d("height").toInt(),
                tbr = d("tbr"),
                abr = d("abr"),
                filesize = d("filesize").toLong(),
                filesizeApprox = d("filesize_approx").toLong(),
                protocol = f.optString("protocol"),
            )
        }
        return list.filter { it.id.isNotBlank() }
    }

    private fun bestThumbnail(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        var best: JSONObject? = null
        var bestScore = Long.MIN_VALUE
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (!t.optString("url").startsWith("http")) continue
            val score = t.optLong("width", 0) * t.optLong("height", 0) * 10 + t.optInt("preference", 0) + i
            if (score >= bestScore) { bestScore = score; best = t }
        }
        return best?.optString("url")
    }

    private fun xImages(url: String): List<MediaItem.Image> {
        val (user, id) = UrlDetector.xStatus(url) ?: return emptyList()
        val json = JSONObject(Http.getText("https://api.vxtwitter.com/$user/status/$id"))
        val media = json.optJSONArray("media_extended") ?: return emptyList()
        val list = mutableListOf<MediaItem.Image>()
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            if (m.optString("type") != "image") continue
            var u = m.optString("url")
            if (!u.startsWith("http")) continue
            if (u.contains("pbs.twimg.com/media/") && !u.contains("name=")) u += (if (u.contains('?')) "&" else "?") + "name=orig"
            list += MediaItem.Image("${user}_${id}_${list.size + 1}", u, guessExt(u), 0)
        }
        return list
    }

    private fun ogImages(url: String): List<MediaItem.Image> {
        val html = Http.getText(url)
        val re1 = Regex("""<meta[^>]+(?:property|name)=["'](?:og:image|og:image:url|twitter:image)["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val re2 = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*(?:property|name)=["'](?:og:image|og:image:url|twitter:image)["']""", RegexOption.IGNORE_CASE)
        val urls = (re1.findAll(html) + re2.findAll(html)).map { it.groupValues[1].replace("&amp;", "&") }
            .filter { it.startsWith("http") }.distinct().toList()
        return urls.mapIndexed { i, u -> MediaItem.Image("image_${i + 1}", u, guessExt(u), 0) }
    }

    companion object {
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "avif")

        fun guessExt(u: String): String {
            Regex("""[?&]format=(\w+)""").find(u)?.let { return it.groupValues[1].lowercase() }
            val path = u.substringBefore('?').substringAfterLast('/')
            val e = path.substringAfterLast('.', "").lowercase()
            return if (e in IMAGE_EXT) e else "jpg"
        }
    }
}
