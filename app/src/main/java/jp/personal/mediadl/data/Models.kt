package jp.personal.mediadl.data

import jp.personal.mediadl.core.Fmt
import jp.personal.mediadl.core.ServiceType

sealed class MediaItem {
    abstract val title: String

    data class Video(
        override val title: String,
        val id: String,
        /** プレイリスト（複数メディア投稿）内の番号。単体なら null */
        val playlistIndex: Int?,
        val durationSec: Double,
        val formats: List<Fmt>,
        val infoSize: Long,
    ) : MediaItem()

    data class Image(
        override val title: String,
        val url: String,
        val ext: String,
        /** HEAD で得たサイズ（0=不明） */
        val size: Long,
    ) : MediaItem()
}

data class Analysis(
    val url: String,
    val service: ServiceType,
    val title: String,
    val items: List<MediaItem>,
    val notes: List<String> = emptyList(),
) {
    val videos get() = items.filterIsInstance<MediaItem.Video>()
    val images get() = items.filterIsInstance<MediaItem.Image>()
}
