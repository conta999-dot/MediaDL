package jp.personal.mediadl.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import jp.personal.mediadl.Engine
import jp.personal.mediadl.core.FormatSelector
import jp.personal.mediadl.core.Quality
import jp.personal.mediadl.core.ServiceType
import jp.personal.mediadl.core.StorageCheck
import jp.personal.mediadl.core.StorageVerdict
import jp.personal.mediadl.core.UrlDetector
import jp.personal.mediadl.data.Analysis
import jp.personal.mediadl.data.MediaAnalyzer
import jp.personal.mediadl.data.MediaItem
import jp.personal.mediadl.data.Settings
import jp.personal.mediadl.service.DownloadJob
import jp.personal.mediadl.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Estimate(val bytes: Long, val complete: Boolean, val videoBytes: Long, val summary: String, val label: String)

data class UiState(
    val url: String = "",
    val service: ServiceType = ServiceType.UNKNOWN,
    val analyzing: Boolean = false,
    val analysis: Analysis? = null,
    val error: String? = null,
    val quality: Quality = Quality.BEST,
    val estimates: Map<Quality, Estimate> = emptyMap(),
    val free: Long = 0,
    val total: Long = 0,
    val dialog: StorageVerdict? = null,
    // 設定
    val folderLabel: String = Settings.DEFAULT_LABEL,
    val hasCustomFolder: Boolean = false,
    val hasCookies: Boolean = false,
    val useCookies: Boolean = true,
    val ytdlpVersion: String = "",
    val updating: Boolean = false,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val analyzer = MediaAnalyzer(settings)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private var analyzeJob: Job? = null

    init {
        refreshSettings()
        refreshStorage()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Engine.await() }
                .onSuccess { _ui.update { it.copy(ytdlpVersion = YoutubeDL.getInstance().version(getApplication()) ?: "") } }
                .onFailure { e -> _ui.update { it.copy(error = "yt-dlp の初期化に失敗: ${e.message}") } }
        }
    }

    fun onUrlChange(text: String) {
        _ui.update { it.copy(url = text, service = UrlDetector.detect(UrlDetector.extractUrl(text) ?: text), error = null) }
    }

    /** 共有インテント（text/plain）から受け取ったテキスト */
    fun onShared(text: String?) {
        val u = UrlDetector.extractUrl(text) ?: return
        onUrlChange(u)
        analyze()
    }

    fun clear() { analyzeJob?.cancel(); _ui.update { it.copy(url = "", service = ServiceType.UNKNOWN, analysis = null, error = null, analyzing = false) } }

    fun analyze() {
        val url = UrlDetector.extractUrl(_ui.value.url) ?: run {
            _ui.update { it.copy(error = "URL を認識できません") }; return
        }
        analyzeJob?.cancel()
        _ui.update { it.copy(url = url, service = UrlDetector.detect(url), analyzing = true, analysis = null, error = null) }
        analyzeJob = viewModelScope.launch {
            try {
                val a = withContext(Dispatchers.IO) { analyzer.analyze(url) }
                val est = Quality.entries.associateWith { estimate(a, it) }
                refreshStorage()
                _ui.update { it.copy(analyzing = false, analysis = a, estimates = est) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(analyzing = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun setQuality(q: Quality) = _ui.update { it.copy(quality = q) }

    private fun estimate(a: Analysis, q: Quality): Estimate {
        var total = 0L; var video = 0L; var complete = true
        val parts = mutableListOf<String>()
        val heights = mutableListOf<Int>()
        a.items.forEach { item ->
            when (item) {
                is MediaItem.Image -> if (item.size > 0) total += item.size else complete = false
                is MediaItem.Video -> {
                    val s = FormatSelector.select(item.formats, item.durationSec, q, item.infoSize)
                    if (s.estimatedBytes > 0) { total += s.estimatedBytes; video += s.estimatedBytes } else complete = false
                    parts += s.summary
                    heights += s.height
                }
            }
        }
        // 実際にダウンロードされる画質をラベルに反映（複数動画は最大値で表示）
        val label = q.displayLabel(heights.maxOrNull() ?: 0)
        return Estimate(total, complete, video, parts.distinct().joinToString(" / "), label)
    }

    fun refreshStorage() {
        val ext = runCatching { StatFs(Environment.getExternalStorageDirectory().path) }.getOrNull()
        val data = runCatching { StatFs(getApplication<Application>().cacheDir.path) }.getOrNull()
        val free = listOfNotNull(ext?.availableBytes, data?.availableBytes).minOrNull() ?: 0L
        val total = ext?.totalBytes ?: data?.totalBytes ?: 0L
        _ui.update { it.copy(free = free, total = total) }
    }

    /** ダウンロード前チェック：想定サイズと空き容量を比較し、必要なら警告ダイアログ */
    fun requestDownload() {
        val s = _ui.value
        val a = s.analysis ?: return
        refreshStorage()
        val est = s.estimates[s.quality] ?: estimate(a, s.quality)
        // 動画は一時ファイル→保存先コピーのため、処理中は動画サイズ分を余分に必要とする
        val verdict = StorageCheck.evaluate(est.bytes, est.bytes + est.videoBytes, _ui.value.free, _ui.value.total)
        when (verdict) {
            is StorageVerdict.Ok -> startDownload()
            else -> _ui.update { it.copy(dialog = verdict) }
        }
    }

    fun dismissDialog() = _ui.update { it.copy(dialog = null) }

    fun startDownload() {
        val a = _ui.value.analysis ?: return
        _ui.update { it.copy(dialog = null) }
        DownloadService.start(getApplication(), DownloadJob(a, _ui.value.quality))
    }

    fun cancelDownload() = DownloadService.cancel(getApplication())

    // ---- 設定 ----
    fun refreshSettings() {
        _ui.update {
            it.copy(
                folderLabel = settings.treeLabel ?: Settings.DEFAULT_LABEL,
                hasCustomFolder = settings.treeUri != null,
                hasCookies = settings.hasCookies,
                useCookies = settings.useCookies,
            )
        }
    }

    fun onFolderPicked(uri: Uri?) {
        uri ?: return
        val cr = getApplication<Application>().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            cr.takePersistableUriPermission(uri, flags) // 再起動後も権限を保持
            val name = DocumentFile.fromTreeUri(getApplication(), uri)?.name
                ?: Uri.decode(uri.lastPathSegment ?: "").substringAfter(':')
            settings.treeUri?.takeIf { it != uri }?.let { old -> runCatching { cr.releasePersistableUriPermission(old, flags) } }
            settings.treeUri = uri
            settings.treeLabel = name
            toast("保存先を「$name」に設定しました")
        } catch (e: Exception) {
            toast("フォルダの権限を取得できませんでした: ${e.message}")
        }
        refreshSettings()
    }

    fun resetFolder() {
        val cr = getApplication<Application>().contentResolver
        settings.treeUri?.let { runCatching { cr.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        settings.treeUri = null; settings.treeLabel = null
        refreshSettings(); toast("保存先を既定 (Download/MediaDL) に戻しました")
    }

    fun onCookiesPicked(uri: Uri?) {
        uri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("読み込めません")
                val valid = text.lineSequence().any { l -> !l.startsWith("#") && l.split('\t').size >= 7 }
                if (!valid) throw IllegalStateException("Netscape形式の cookies.txt ではないようです")
                settings.cookiesFile.writeText(text)
                settings.useCookies = true
                toast("cookies.txt を取り込みました")
            } catch (e: Exception) {
                toast("取り込み失敗: ${e.message}")
            }
            refreshSettings()
        }
    }

    fun setUseCookies(v: Boolean) { settings.useCookies = v; refreshSettings() }

    /** アプリ内に取り込んだ cookies.txt のコピーのみを消す（元ファイルには触れない） */
    fun removeCookies() { settings.cookiesFile.delete(); refreshSettings(); toast("取り込んだ cookie を解除しました") }

    fun updateYtdlp() {
        if (_ui.value.updating) return
        _ui.update { it.copy(updating = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Engine.await()
                val st = YoutubeDL.getInstance().updateYoutubeDL(getApplication(), YoutubeDL.UpdateChannel.STABLE)
                val v = YoutubeDL.getInstance().version(getApplication()) ?: ""
                _ui.update { it.copy(ytdlpVersion = v) }
                toast(if (st == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "yt-dlp は最新です ($v)" else "yt-dlp を更新しました ($v)")
            } catch (e: Exception) {
                toast("更新失敗: ${e.message}")
            } finally {
                _ui.update { it.copy(updating = false) }
            }
        }
    }

    private fun toast(msg: String) = _ui.update { it.copy(message = msg) }
    fun messageShown() = _ui.update { it.copy(message = null) }
}
