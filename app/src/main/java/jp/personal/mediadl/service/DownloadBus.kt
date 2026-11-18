package jp.personal.mediadl.service

import jp.personal.mediadl.core.Quality
import jp.personal.mediadl.data.Analysis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DownloadJob(val analysis: Analysis, val quality: Quality)

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Running(
        val percent: Int,
        val current: Int,
        val total: Int,
        val label: String,
        val detail: String = "",
    ) : DownloadState()
    data class Done(val saved: List<String>, val failed: List<String>, val folder: String) : DownloadState()
    data class Failed(val message: String) : DownloadState()
    data object Cancelled : DownloadState()
}

/** UI とフォアグラウンドサービスの間で状態を共有する（同一プロセス内）。 */
object DownloadBus {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    @Volatile var pending: DownloadJob? = null

    fun update(s: DownloadState) { _state.value = s }
    val isRunning get() = _state.value is DownloadState.Running
}
