package jp.personal.mediadl.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** ダウンロード日ごとのサブフォルダ名 DL_YYYYMMDD（ダウンロード実行時の日付で毎回生成）。 */
object DateFolder {
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    fun name(date: LocalDate = LocalDate.now()): String = "DL_" + date.format(fmt)
}
