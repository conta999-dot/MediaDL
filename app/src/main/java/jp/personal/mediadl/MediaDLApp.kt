package jp.personal.mediadl

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaDLApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
        Engine.start(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "ダウンロード進捗", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "ダウンロード完了", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
    }
}

/** yt-dlp / ffmpeg の初期化（初回は Python 環境の展開に数秒かかる）。 */
object Engine {
    val ready = CompletableDeferred<Result<Unit>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(app: Application) {
        scope.launch {
            val r = runCatching {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            }
            ready.complete(r)
        }
    }

    suspend fun await() = ready.await().getOrThrow()
}
