package jp.personal.mediadl.core

import kotlin.math.max

/** 想定サイズと空き容量の比較結果。 */
sealed class StorageVerdict {
    /** 問題なし */
    data class Ok(val freeAfter: Long) : StorageVerdict()
    /** 実行は可能だが、終了後の空きが少なくなる（警告して続行/中止を選択） */
    data class Tight(val freeAfter: Long, val threshold: Long) : StorageVerdict()
    /** サイズが不明（警告して続行/中止を選択） */
    data class Unknown(val free: Long, val threshold: Long) : StorageVerdict()
    /** 空き容量を超えるため実行不可 */
    data class Block(val required: Long, val free: Long) : StorageVerdict()
}

object StorageCheck {
    const val GIB = 1024L * 1024L * 1024L

    /** ひっ迫とみなすしきい値: max(1GB, 総容量の10%) */
    fun threshold(total: Long): Long = max(GIB, total / 10)

    /**
     * @param finalBytes 保存後に残るファイルの想定合計サイズ（0以下=不明）
     * @param peakBytes  処理中に一時的に必要になる最大サイズ（動画は一時ファイル+コピーで約2倍）
     * @param free 端末の空き容量 (StatFs)
     * @param total 端末の総容量 (StatFs)
     */
    fun evaluate(finalBytes: Long, peakBytes: Long, free: Long, total: Long): StorageVerdict {
        val th = threshold(total)
        if (finalBytes <= 0L) return StorageVerdict.Unknown(free, th)
        val peak = max(peakBytes, finalBytes)
        if (peak > free) return StorageVerdict.Block(peak, free)
        val after = free - finalBytes
        return if (after < th) StorageVerdict.Tight(after, th) else StorageVerdict.Ok(after)
    }

    fun human(bytes: Long): String {
        if (bytes < 0) return "不明"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i <= 1) String.format("%.0f %s", v, units[i]) else String.format("%.1f %s", v, units[i])
    }
}
