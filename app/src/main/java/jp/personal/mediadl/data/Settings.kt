package jp.personal.mediadl.data

import android.content.Context
import android.net.Uri
import java.io.File

/** アプリ設定（SharedPreferences）。 */
class Settings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** SAF で選んだ保存先ツリー URI。null の場合は既定 (Download/MediaDL)。 */
    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
        set(v) = prefs.edit().putString(KEY_TREE, v?.toString()).apply()

    var treeLabel: String?
        get() = prefs.getString(KEY_TREE_LABEL, null)
        set(v) = prefs.edit().putString(KEY_TREE_LABEL, v).apply()

    var useCookies: Boolean
        get() = prefs.getBoolean(KEY_USE_COOKIES, true)
        set(v) = prefs.edit().putBoolean(KEY_USE_COOKIES, v).apply()

    /** 取り込んだ cookies.txt（アプリ専用領域に保存） */
    val cookiesFile: File get() = File(app.filesDir, "cookies.txt")
    val hasCookies: Boolean get() = cookiesFile.isFile && cookiesFile.length() > 0

    fun activeCookies(): File? = if (useCookies && hasCookies) cookiesFile else null

    companion object {
        private const val KEY_TREE = "tree_uri"
        private const val KEY_TREE_LABEL = "tree_label"
        private const val KEY_USE_COOKIES = "use_cookies"
        const val DEFAULT_LABEL = "内部ストレージ/Download/MediaDL（既定）"
    }
}
