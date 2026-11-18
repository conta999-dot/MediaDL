package jp.personal.mediadl.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import jp.personal.mediadl.Engine
import jp.personal.mediadl.MediaDLApp
import jp.personal.mediadl.R
import jp.personal.mediadl.core.DateFolder
import jp.personal.mediadl.core.FormatSelector
import jp.personal.mediadl.core.ServiceType
import jp.personal.mediadl.data.Http
import jp.personal.mediadl.data.MediaItem
import jp.personal.mediadl.data.OutputHandle
import jp.personal.mediadl.data.OutputWriter
import jp.personal.mediadl.data.Settings
import jp.personal.mediadl.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** ダウンロード本体。バックグラウンドに回っても止まらないようフォアグラウンドサービスで実行する。 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var processId: String? = null
    @Volatile private var openHandle: OutputHandle? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { cancelDownload(); return START_NOT_STICKY }
            ACTION_START -> {
                val dj = DownloadBus.pending
                if (job?.isActive == true) return START_NOT_STICKY
                if (dj == null) {
                    // startForegroundService の約束を守るため一度 foreground にしてから終了
                    ServiceCompat.startForeground(
                        this, NOTIF_PROGRESS, progressNotification("待機中", 0, true),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                DownloadBus.pending = null
                ServiceCompat.startForeground(
                    this, NOTIF_PROGRESS, progressNotification("準備中…", 0, true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaDL:download").apply { acquire(3 * 60 * 60 * 1000L) }
                job = scope.launch { runJob(dj) }
            }
        }
        return START_NOT_STICKY
    }

    private fun cancelDownload() {
        processId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
        job?.cancel()
        if (job == null) stopSelf()
    }

    private suspend fun runJob(dj: DownloadJob) {
        val settings = Settings(this)
        val writer = OutputWriter(this, settings)
        // 日付フォルダはダウンロード実行時点の日付で生成
        val dateFolder = DateFolder.name()
        val items = dj.analysis.items
        val saved = mutableListOf<String>()
        val failed = mutableListOf<String>()

        // 進捗の重み: 全件のサイズが分かればサイズ比、そうでなければ件数比
        val sizes = items.map {
            when (it) {
                is MediaItem.Image -> it.size
                is MediaItem.Video -> FormatSelector.select(it.formats, it.durationSec, dj.quality, it.infoSize).estimatedBytes
            }
        }
        val weights = if (sizes.all { it > 0 }) sizes.map { it.toDouble() } else items.map { 1.0 }
        val totalW = weights.sum().coerceAtLeast(1.0)
        var doneW = 0.0
        val imageCount = items.count { it is MediaItem.Image }
        var imageNo = 0

        try {
            Engine.await()
            items.forEachIndexed { i, item ->
                coroutineContext.ensureActive()
                val label = "${i + 1}/${items.size} ${if (item is MediaItem.Video) "動画" else "画像"}: ${item.title.take(40)}"
                val report: (Double, String) -> Unit = { frac, detail ->
                    val pct = (((doneW + weights[i] * frac.coerceIn(0.0, 1.0)) / totalW) * 100).toInt().coerceIn(0, 100)
                    publish(DownloadState.Running(pct, i + 1, items.size, label, detail))
                }
                report(0.0, "")
                try {
                    val path = when (item) {
                        is MediaItem.Image -> {
                            imageNo++
                            val suffix = if (imageCount > 1) "_${"%02d".format(imageNo)}" else ""
                            downloadImage(item, writer, dateFolder, "${item.title.take(60)}$suffix.${item.ext}", report)
                        }
                        is MediaItem.Video -> downloadVideo(dj, item, writer, dateFolder, report)
                    }
                    saved += path
                } catch (e: CancellationException) {
                    throw e
                } catch (e: YoutubeDL.CanceledException) {
                    throw CancellationException("cancelled")
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    failed += "${item.title.take(40)}: ${(e.message ?: e.toString()).lineSequence().lastOrNull { it.isNotBlank() }?.take(200)}"
                }
                doneW += weights[i]
            }
            val folder = (settings.treeLabel ?: "Download/MediaDL") + "/" + dateFolder
            DownloadBus.update(DownloadState.Done(saved, failed, folder))
            notifyDone(
                if (failed.isEmpty()) "ダウンロード完了（${saved.size}件）" else "完了 ${saved.size}件 / 失敗 ${failed.size}件",
                folder
            )
        } catch (e: CancellationException) {
            openHandle?.abort()
            DownloadBus.update(DownloadState.Cancelled)
            notifyDone("ダウンロードをキャンセルしました", "保存済み ${saved.size}件")
        } catch (e: Exception) {
            openHandle?.abort()
            DownloadBus.update(DownloadState.Failed(e.message ?: e.toString()))
            notifyDone("ダウンロードに失敗しました", e.message ?: "")
        } finally {
            openHandle = null
            runCatching { wakeLock?.release() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun downloadImage(
        item: MediaItem.Image, writer: OutputWriter, dateFolder: String, name: String, report: (Double, String) -> Unit,
    ): String {
        val conn = Http.open(item.url)
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val h = writer.create(dateFolder, name)
            openHandle = h
            conn.inputStream.use { input -> copy(input, h.stream, total, report) }
            h.commit(); openHandle = null
            return h.displayPath
        } finally { conn.disconnect() }
    }

    private suspend fun downloadVideo(
        dj: DownloadJob, item: MediaItem.Video, writer: OutputWriter, dateFolder: String, report: (Double, String) -> Unit,
    ): String {
        val sel = FormatSelector.select(item.formats, item.durationSec, dj.quality, item.infoSize)
        val tmp = File(cacheDir, "dl/${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val req = YoutubeDLRequest(dj.analysis.url)
                .addOption("-f", sel.formatSpec)
                .addOption("-o", "${tmp.absolutePath}/%(id)s.%(ext)s")
                .addOption("--merge-output-format", "mp4")
                .addOption("--no-mtime")
                .addOption("--no-warnings")
            if (dj.analysis.service == ServiceType.YOUTUBE) req.addOption("--no-playlist")
            item.playlistIndex?.let { req.addOption("--playlist-items", it.toString()) }
            Settings(this).activeCookies()?.let { req.addOption("--cookies", it.absolutePath) }

            val pid = "dl-${System.nanoTime()}"
            processId = pid
            YoutubeDL.getInstance().execute(req, pid) { progress, eta, _ ->
                val etaText = if (eta > 0) "残り約${eta}秒" else ""
                report((progress / 100.0) * 0.95, "ダウンロード中 ${progress.toInt()}% $etaText")
            }
            processId = null
            coroutineContext.ensureActive()

            val file = tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() } ?: throw java.io.IOException("出力ファイルが見つかりません")
            val h = writer.create(dateFolder, "${item.title.take(80)} [${item.id}].${file.extension}")
            openHandle = h
            file.inputStream().use { input ->
                copy(input, h.stream, file.length()) { f, _ -> report(0.95 + f * 0.05, "保存先へコピー中") }
            }
            h.commit(); openHandle = null
            return h.displayPath
        } finally {
            processId = null
            // アプリ専用キャッシュ内の一時ファイルのみ削除（ユーザーのファイルには触れない）
            tmp.deleteRecursively()
        }
    }

    private suspend fun copy(input: InputStream, out: OutputStream, total: Long, report: (Double, String) -> Unit) {
        val buf = ByteArray(128 * 1024)
        var done = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf); if (n < 0) break
            out.write(buf, 0, n); done += n
            if (total > 0) report(done.toDouble() / total, "") else report(0.5, "")
        }
        out.flush()
    }

    private fun publish(s: DownloadState.Running) {
        DownloadBus.update(s)
        val now = System.currentTimeMillis()
        if (now - lastNotify > 700) {
            lastNotify = now
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_PROGRESS, progressNotification(s.label, s.percent, false))
        }
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotification(text: String, pct: Int, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, MediaDLApp.CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("ダウンロード中 $pct%")
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "キャンセル", cancel)
            .build()
    }

    private fun notifyDone(title: String, text: String) {
        val n = NotificationCompat.Builder(this, MediaDLApp.CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n) }
    }

    override fun onDestroy() {
        processId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "jp.personal.mediadl.START"
        const val ACTION_CANCEL = "jp.personal.mediadl.CANCEL"
        private const val NOTIF_PROGRESS = 1001
        private const val NOTIF_DONE = 1002

        fun start(context: Context, job: DownloadJob) {
            DownloadBus.pending = job
            DownloadBus.update(DownloadState.Running(0, 0, job.analysis.items.size, "準備中…"))
            context.startForegroundService(Intent(context, DownloadService::class.java).setAction(ACTION_START))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
