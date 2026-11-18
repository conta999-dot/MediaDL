package jp.personal.mediadl.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.personal.mediadl.BuildConfig
import jp.personal.mediadl.core.Quality
import jp.personal.mediadl.core.ServiceType
import jp.personal.mediadl.core.StorageCheck
import jp.personal.mediadl.core.StorageCheck.human
import jp.personal.mediadl.core.StorageVerdict
import jp.personal.mediadl.service.DownloadBus
import jp.personal.mediadl.service.DownloadState

@Composable
fun AppRoot(vm: MainViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }
    BackHandler(enabled = showSettings) { showSettings = false }
    if (showSettings) SettingsScreen(vm, ui, snackbar) { showSettings = false }
    else MainScreen(vm, ui, snackbar) { showSettings = true }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, ui: UiState, snackbar: SnackbarHostState, openSettings: () -> Unit) {
    val dl by DownloadBus.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MediaDL", fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, "設定") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeroHeader()
            OutlinedTextField(
                value = ui.url,
                onValueChange = vm::onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("動画・画像の URL") },
                placeholder = { Text("https://…") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Filled.Link, null) },
                trailingIcon = {
                    if (ui.url.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Filled.Close, "クリア") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.analyze() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { vm.onUrlChange(it); vm.analyze() }
                }) { Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("貼り付けて解析") }
                Button(onClick = vm::analyze, enabled = ui.url.isNotBlank() && !ui.analyzing) {
                    Icon(Icons.Filled.Search, null); Spacer(Modifier.width(6.dp)); Text("解析")
                }
            }
            if (ui.url.isNotBlank()) ServiceBadge(ui.service)

            if (ui.analyzing) {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("メディア情報を取得中…（初回は準備に数秒かかります）")
                    }
                }
            }
            ui.error?.let { ErrorCard(it) }
            ui.analysis?.let { AnalysisCard(vm, ui) }
            DownloadCard(dl, vm)
            StorageInfo(ui)
        }
    }
    ui.dialog?.let { StorageDialog(it, vm) }
}

@Composable
private fun HeroHeader() {
    Box(
        Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(Color(0xFF5B4BFF), Color(0xFF00C2FF))), RoundedCornerShape(24.dp)
        ).padding(20.dp)
    ) {
        Column {
            Text("URLを貼るだけ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("YouTube・X・Instagram・Facebook・TikTok を自動判別", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
        }
    }
}

@Composable
fun ServiceBadge(s: ServiceType) {
    val c = Color(s.color)
    Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.15f), border = BorderStroke(1.dp, c.copy(alpha = 0.6f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(c, CircleShape), contentAlignment = Alignment.Center) {
                Text(s.glyph, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (s == ServiceType.UNKNOWN) "判別できません（試行は可能）" else "${s.label} を検出", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ErrorCard(msg: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AnalysisCard(vm: MainViewModel, ui: UiState) {
    val a = ui.analysis ?: return
    val running = DownloadBus.state.collectAsStateWithLifecycle().value is DownloadState.Running
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.videos.isNotEmpty()) AssistChip(onClick = {}, label = { Text("動画 ${a.videos.size}本") }, leadingIcon = { Icon(Icons.Filled.Movie, null) })
                if (a.images.isNotEmpty()) AssistChip(onClick = {}, label = { Text("画像 ${a.images.size}枚") }, leadingIcon = { Icon(Icons.Filled.Image, null) })
            }
            if (a.images.size > 1) Text("複数の画像はまとめて一括ダウンロードします", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            a.notes.forEach { Text("※ $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (a.videos.isNotEmpty()) {
                Text("画質を選択", fontWeight = FontWeight.SemiBold)
                Quality.entries.forEach { q ->
                    val e = ui.estimates[q]
                    val selected = ui.quality == q
                    OutlinedCard(
                        onClick = { vm.setQuality(q) },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = { vm.setQuality(q) })
                            Column(Modifier.weight(1f)) {
                                Text(e?.label ?: q.displayLabel(), fontWeight = FontWeight.SemiBold)
                                Text(e?.summary ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(sizeText(e), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else {
                Text("想定サイズ: ${sizeText(ui.estimates[Quality.BEST])}", fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = vm::requestDownload, enabled = !running,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp))
                Text(if (a.videos.isEmpty() && a.images.size > 1) "${a.images.size}枚を一括ダウンロード" else "ダウンロード", fontSize = 16.sp)
            }
        }
    }
}

private fun sizeText(e: Estimate?): String = when {
    e == null || e.bytes <= 0 -> "サイズ不明"
    e.complete -> "約 ${human(e.bytes)}"
    else -> "約 ${human(e.bytes)} 以上"
}

@Composable
private fun DownloadCard(s: DownloadState, vm: MainViewModel) {
    AnimatedVisibility(visible = s !is DownloadState.Idle) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s) {
                    is DownloadState.Running -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.percent}%", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                            Text(s.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        }
                        LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp))
                        if (s.detail.isNotBlank()) Text(s.detail, fontSize = 12.sp)
                        OutlinedButton(onClick = vm::cancelDownload) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("キャンセル") }
                    }
                    is DownloadState.Done -> {
                        Text(if (s.failed.isEmpty()) "✅ ダウンロード完了（${s.saved.size}件）" else "⚠ 完了 ${s.saved.size}件 / 失敗 ${s.failed.size}件", fontWeight = FontWeight.Bold)
                        Text("保存先: ${s.folder}", fontSize = 12.sp)
                        s.failed.take(5).forEach { Text("・$it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    }
                    is DownloadState.Failed -> Text("❌ 失敗: ${s.message}", fontSize = 13.sp)
                    DownloadState.Cancelled -> Text("キャンセルしました（未完成のファイルは保存していません）", fontSize = 13.sp)
                    DownloadState.Idle -> {}
                }
            }
        }
    }
}

@Composable
private fun StorageInfo(ui: UiState) {
    if (ui.total <= 0) return
    val used = 1f - ui.free.toFloat() / ui.total
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("端末の空き容量: ${human(ui.free)} / ${human(ui.total)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth().height(4.dp))
        Text("警告ライン: ダウンロード後の空きが ${human(StorageCheck.threshold(ui.total))} 未満", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StorageDialog(v: StorageVerdict, vm: MainViewModel) {
    when (v) {
        is StorageVerdict.Block -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            icon = { Icon(Icons.Filled.Block, null) },
            title = { Text("空き容量が足りません") },
            text = { Text("必要な容量（一時ファイル含む）: 約${human(v.required)}\n空き容量: ${human(v.free)}\n\n不要なファイルを整理するか、低い画質を選んでください。") },
            confirmButton = { TextButton(onClick = vm::dismissDialog) { Text("閉じる") } },
        )
        is StorageVerdict.Tight -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text("容量がひっ迫します") },
            text = { Text("ダウンロード後の空き容量が約${human(v.freeAfter.coerceAtLeast(0))}になり、目安（${human(v.threshold)}）を下回ります。端末の動作が不安定になるおそれがあります。\n\n続行しますか？") },
            confirmButton = { TextButton(onClick = vm::startDownload) { Text("続行") } },
            dismissButton = { TextButton(onClick = vm::dismissDialog) { Text("中止") } },
        )
        is StorageVerdict.Unknown -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            icon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, null) },
            title = { Text("サイズが不明です") },
            text = { Text("このメディアは事前にファイルサイズを取得できませんでした。\n現在の空き容量: ${human(v.free)}" + (if (v.free < v.threshold) "（すでに目安 ${human(v.threshold)} を下回っています）" else "") + "\n\n続行しますか？") },
            confirmButton = { TextButton(onClick = vm::startDownload) { Text("続行") } },
            dismissButton = { TextButton(onClick = vm::dismissDialog) { Text("中止") } },
        )
        is StorageVerdict.Ok -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, ui: UiState, snackbar: SnackbarHostState, back: () -> Unit) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onFolderPicked(it) }
    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onCookiesPicked(it) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingCard("保存先フォルダ", Icons.Filled.Folder) {
                Text(ui.folderLabel, fontWeight = FontWeight.SemiBold)
                Text("この下に DL_YYYYMMDD（ダウンロードした日付）フォルダを自動作成して保存します。", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { folderPicker.launch(null) }) { Text("フォルダを選択") }
                    if (ui.hasCustomFolder) OutlinedButton(onClick = vm::resetFolder) { Text("既定に戻す") }
                }
            }
            SettingCard("ログイン用 cookies.txt（任意）", Icons.Filled.Cookie) {
                Text("Instagram・Facebook・X の非公開/年齢制限付き投稿などは、ブラウザから書き出した Netscape 形式の cookies.txt が必要な場合があります。アプリ内にコピーして yt-dlp に渡します。", fontSize = 12.sp)
                Text(if (ui.hasCookies) "取り込み済み" else "未設定", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("cookie を使う", Modifier.weight(1f))
                    Switch(checked = ui.useCookies, onCheckedChange = vm::setUseCookies, enabled = ui.hasCookies)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { cookiePicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) { Text("取り込む") }
                    if (ui.hasCookies) OutlinedButton(onClick = vm::removeCookies) { Text("解除") }
                }
            }
            SettingCard("yt-dlp", Icons.Filled.SystemUpdate) {
                Text("バージョン: ${ui.ytdlpVersion.ifBlank { "準備中…" }}")
                Text("サイトの仕様変更で取得できなくなったら更新してください。", fontSize = 12.sp)
                Button(onClick = vm::updateYtdlp, enabled = !ui.updating) {
                    if (ui.updating) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text("yt-dlpを更新")
                }
            }
            SettingCard("サービスごとの注意", Icons.Filled.Info) {
                Text(
                    "・YouTube: 通常は利用可。仕様変更が多いので定期的に yt-dlp を更新。\n" +
                        "・X: 動画は yt-dlp、画像は vxtwitter 公開API経由。鍵アカ・センシティブ設定は cookie が必要。\n" +
                        "・Instagram: ログインが必要なことが多い（cookie 推奨）。\n" +
                        "・Facebook: 公開動画のみ。写真はプレビュー画像になる場合あり。\n" +
                        "・TikTok: 動画は可。写真スライドはプレビュー画像のみの場合あり。\n" +
                        "・個人利用の範囲で、各サービスの規約と著作権を守ってご利用ください。",
                    fontSize = 12.sp,
                )
            }
            SettingCard("バージョン", Icons.Filled.Tag) {
                Text("MediaDL v${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}
