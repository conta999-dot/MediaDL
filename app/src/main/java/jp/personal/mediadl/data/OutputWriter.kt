package jp.personal.mediadl.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** 書き込み中の出力ファイル。commit で確定、abort で（アプリが作った未完成ファイルのみ）破棄。 */
interface OutputHandle {
    val stream: OutputStream
    val displayPath: String
    fun commit()
    fun abort()
}

/**
 * 保存先フォルダ（SAF ツリー or 既定 Download/MediaDL）の下に DL_YYYYMMDD を作って書き込む。
 * 既存ファイルは上書きしない（同名は自動で別名になる）。
 */
class OutputWriter(private val context: Context, private val settings: Settings) {

    fun create(dateFolder: String, fileName: String): OutputHandle {
        val name = sanitize(fileName)
        val mime = mimeOf(name)
        val tree = settings.treeUri
        return when {
            tree != null -> createSaf(tree, dateFolder, name, mime)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> createMediaStore(dateFolder, name, mime)
            else -> createLegacy(dateFolder, name)
        }
    }

    private fun createSaf(tree: Uri, dateFolder: String, name: String, mime: String): OutputHandle {
        val root = DocumentFile.fromTreeUri(context, tree)
            ?: throw IOException("保存先フォルダにアクセスできません。設定で選び直してください")
        if (!root.canWrite()) throw IOException("保存先フォルダへの書き込み権限がありません。設定で選び直してください")
        val dir = root.findFile(dateFolder)?.takeIf { it.isDirectory }
            ?: root.createDirectory(dateFolder) ?: throw IOException("$dateFolder を作成できません")
        val doc = dir.createFile(mime, name) ?: throw IOException("$name を作成できません")
        val os = context.contentResolver.openOutputStream(doc.uri, "w") ?: throw IOException("書き込みを開始できません")
        return object : OutputHandle {
            override val stream = os
            override val displayPath = "${settings.treeLabel ?: "選択フォルダ"}/$dateFolder/${doc.name ?: name}"
            override fun commit() { os.close() }
            override fun abort() { runCatching { os.close() }; runCatching { doc.delete() } }
        }
    }

    private fun createMediaStore(dateFolder: String, name: String, mime: String): OutputHandle {
        val rel = "${Environment.DIRECTORY_DOWNLOADS}/MediaDL/$dateFolder/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Download/MediaDL に書き込めません")
        val os = cr.openOutputStream(uri, "w") ?: throw IOException("書き込みを開始できません")
        return object : OutputHandle {
            override val stream = os
            override val displayPath = "Download/MediaDL/$dateFolder/$name"
            override fun commit() {
                os.close()
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            override fun abort() { runCatching { os.close() }; runCatching { cr.delete(uri, null, null) } }
        }
    }

    @Suppress("DEPRECATION")
    private fun createLegacy(dateFolder: String, name: String): OutputHandle {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MediaDL/$dateFolder")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("${dir.path} を作成できません（ストレージ権限を確認してください）")
        var f = File(dir, name)
        var n = 1
        while (f.exists()) {
            f = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "bin")}"); n++
        }
        val os = FileOutputStream(f)
        return object : OutputHandle {
            override val stream = os
            override val displayPath = f.path
            override fun commit() { os.close() }
            override fun abort() { runCatching { os.close() }; runCatching { f.delete() } }
        }
    }

    companion object {
        fun sanitize(n: String): String {
            val cleaned = n.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trim('.')
            val ext = cleaned.substringAfterLast('.', "")
            val base = cleaned.substringBeforeLast('.').take(120).ifBlank { "media" }
            return if (ext.isNotBlank() && ext.length <= 5) "$base.$ext" else base
        }

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: when (ext) { "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "jpg", "jpeg" -> "image/jpeg"; else -> "application/octet-stream" }
        }
    }
}
