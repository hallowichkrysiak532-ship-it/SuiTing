package com.suiting.podcast

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

private data class VoiceProbe(val voice: String, val running: Boolean, val message: String)

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private val importDraft by lazy { ViewModelProvider(this)[ImportDraftModel::class.java] }
    private var systemVoice by mutableStateOf<SystemVoiceStatus?>(null)
    private var voiceProbe by mutableStateOf<VoiceProbe?>(null)
    private var probeJob: Job? = null
    private var probeTicket = 0
    private var systemCheckJob: Job? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val controllerFuture by lazy { MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        receiveShare(intent)
        checkSystemVoice()
        controllerFuture.addListener({
            if (!isDestroyed) runCatching { controller = controllerFuture.get() }.onFailure { message("播放器连接失败：${it.message}") }
        }, ContextCompat.getMainExecutor(this))
        setContent {
            SuiTingTheme {
                PodcastScreen()
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveShare(intent) }
    @Suppress("DEPRECATION")
    private fun receiveShare(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
            if (uri != null) { importDraft.open(); importDraft.file(uri) }
            else intent.getStringExtra(Intent.EXTRA_TEXT)?.let { importDraft.shareText(it) }
        } else if (intent.action == Intent.ACTION_VIEW && intent.data != null) { importDraft.open(); importDraft.file(intent.data!!) }
        setIntent(Intent(this, MainActivity::class.java))
    }
    override fun onDestroy() { MediaController.releaseFuture(controllerFuture); super.onDestroy() }
    private fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun checkSystemVoice() {
        systemCheckJob?.cancel()
        systemVoice = null
        systemCheckJob = lifecycleScope.launch { systemVoice = SystemSpeechEngine.inspect(this@MainActivity) }
    }
    private fun openVoiceSettings() {
        runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { message("请在手机设置中搜索“文字转语音”或“TTS”；手机朗读功能不一定开放给其他应用") }
    }
    private fun probeVoice(requestedVoice: String) {
        val voice = generationVoice(requestedVoice)
        if (probeJob?.isActive == true) { message("正在试听，请稍候"); return }
        val ticket = ++probeTicket
        voiceProbe = VoiceProbe(voice, true, "正在准备试听…")
        if (controller?.currentMediaItem?.mediaId == "voice-check") { controller?.stop(); controller?.clearMediaItems() }
        probeJob = lifecycleScope.launch {
            var engine: SpeechEngine? = null
            try {
                val file = withContext(Dispatchers.IO) {
                    val target = File(cacheDir, "voice-check/" + voice + if (voice == "system") ".wav" else ".mp3")
                    target.parentFile!!.mkdirs(); target.delete()
                    engine = speechEngine(this@MainActivity, voice)
                    engine!!.synthesize("你好，欢迎来到随听。把想读的，留给耳朵。Welcome to your personal podcast.", voice, target)
                    target
                }
                if (ticket != probeTicket) return@launch
                voiceProbe = VoiceProbe(voice, false,
                    if (controller != null) "正在试听" else "播放器尚未就绪，请稍后重试")
                controller?.let { player ->
                    player.setMediaItem(MediaItem.Builder().setMediaId("voice-check").setUri(android.net.Uri.fromFile(file))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("随听 · 音色试听").setArtist(voiceLabel(voice)).build()).build())
                    player.prepare(); player.play()
                }
            } catch (e: CancellationException) { if (ticket == probeTicket && voiceProbe != null) voiceProbe = VoiceProbe(voice, false, "已取消试听") }
            catch (e: Exception) { if (ticket == probeTicket) voiceProbe = VoiceProbe(voice, false, e.message ?: "试听失败") }
            finally { withContext(NonCancellable + Dispatchers.IO) { engine?.close() } }
        }
    }
    private fun copyVoiceReport(probe: VoiceProbe) {
        val report = "随听 " + BuildConfig.VERSION_NAME + "；设备 " + Build.MANUFACTURER + " " + Build.MODEL +
            "；Android " + Build.VERSION.RELEASE + "；音色 " + probe.voice + "\n" + probe.message + "\n系统语音：" + (systemVoice?.message ?: "检测中")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("随听音色试听", report))
        message("已复制诊断")
    }

    private fun generate(episode: Episode) {
        if (library.find(episode.id)?.status == "deleting") return
        if (episode.voice == "system" && systemVoice?.available != true) { message(systemVoice?.message ?: "正在检测系统语音，请稍后重试"); return }
        if (library.episodes.value.any { it.status == "generating" && it.id != episode.id }) { message("请先等待或停止当前生成任务"); return }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            ContextCompat.startForegroundService(this, Intent(this, GenerationService::class.java).putExtra("episode", episode.id))
        }.onFailure { message("无法启动生成：${it.message}") }
    }
    private fun playEpisode(snapshot: Episode, paragraph: Int? = null) {
        val episode = library.find(snapshot.id) ?: return
        if (episode.status == "deleting") return
        val player = controller ?: run { message("播放器正在连接，请稍后再试"); return }
        val count = library.readyCount(episode)
        if (count == 0) return
        val sameEpisode = player.currentMediaItem?.mediaId?.startsWith(episode.id + ":") == true
        val index = (paragraph ?: episode.segmentIndex).coerceIn(0, count - 1)
        val position = if (paragraph == null) episode.positionMs else 0L
        if (!sameEpisode) {
            val restart = paragraph == null && EpisodeTimeline(episode, count).finished()
            player.setMediaItems((0 until count).map { episodeMediaItem(library, episode, it) }, if(restart)0 else index, if(restart)0 else position)
            player.prepare()
        } else {
            appendReadyAudio(player, episode.id, count) { episodeMediaItem(library, episode, it) }
            if (paragraph != null) player.seekTo(index, 0)
            else if (player.playbackState == Player.STATE_ENDED && (count == episode.segments.size || episode.status != "generating")) player.seekTo(0, 0)
        }
        player.play()
    }

    private fun toggleEpisode(episode: Episode) {
        val current = library.find(episode.id) ?: return
        val player = controller
        if (playbackUi(player).requested(current, library.readyCount(current))) player?.pause() else playEpisode(current)
    }
    private fun seekEpisode(episode: Episode, target: ParagraphPosition) {
        val current = library.find(episode.id) ?: return
        val ready = library.readyCount(current)
        if (current.status == "deleting" || target.index !in 0 until ready) return
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId?.startsWith(current.id + ":") != true) playEpisode(current, target.index)
        else appendReadyAudio(player, current.id, ready) { episodeMediaItem(library, current, it) }
        player.seekTo(target.index, target.positionMs)
    }

    @Composable private fun PodcastScreen() {
        val episodes by library.episodes.collectAsStateWithLifecycle()
        val showCreate by importDraft.show.collectAsStateWithLifecycle()
        val draft by importDraft.draft.collectAsStateWithLifecycle()
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        var deleteId by remember { mutableStateOf<String?>(null) }
        var showAbout by remember { mutableStateOf(false) }
        var showVoices by remember { mutableStateOf(false) }
        var coverId by remember { mutableStateOf<String?>(null) }
        var tick by remember { mutableIntStateOf(0) }
        val player = controller
        val selected = episodes.find { it.id == selectedId }
        val ready = remember(episodes) { episodes.associate { it.id to library.readyCount(it) } }
        val playback = remember(player, tick) { playbackUi(player) }
        val resume = episodes.find { it.id == playback.episodeId && (ready[it.id] ?: 0) > 0 }
            ?: episodes.firstOrNull { (ready[it.id] ?: 0) > 0 && (it.segmentIndex > 0 || it.positionMs > 0) }
            ?: episodes.firstOrNull { (ready[it.id] ?: 0) > 0 }
        BackHandler(enabled = selected != null && !showCreate && deleteId == null && coverId == null && !showAbout && !showVoices && voiceProbe == null) { selectedId = null }
        LaunchedEffect(player) { while (true) { delay(500); tick++ } }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { tick++ }
                override fun onPlayerError(error: PlaybackException) { message("播放失败：${error.localizedMessage}") }
            }
            player?.addListener(listener)
            onDispose { player?.removeListener(listener) }
        }
        val actions = EpisodeActions(
            open = { selectedId = it.id }, toggle = ::toggleEpisode, paragraph = { e, index -> playEpisode(e, index) },
            seek = ::seekEpisode, generate = ::generate,
            stop = { startService(Intent(this@MainActivity, GenerationService::class.java).setAction(GenerationService.STOP)); Unit },
            delete = { deleteId = it.id }, probe = ::probeVoice, speed = { player?.setPlaybackSpeed(it) }, cover = {coverId=it.id}
        )
        when {
            showCreate -> CreateScreen(importDraft, systemVoice, ::probeVoice, ::openVoiceSettings, onDismiss = { importDraft.close() }) { title, text, voice, parts ->
                runCatching {
                    val e = Episode(title = title.ifBlank { text.trim().take(24) }, voice = voice, segments = parts, sourceText = text,
                        sourceUrl = draft.source.takeIf{it.startsWith("https://")}.orEmpty(),coverUrl=draft.coverUrl,
                        coverStyle=if(draft.coverStyle==-1&&draft.coverUrl.isBlank())landscapeStyle(draft.title.ifBlank{"随听"},-1)else draft.coverStyle)
                    library.add(e); selectedId = e.id; importDraft.clear(); generate(e)
                }.onFailure { message(it.message ?: "创建失败") }
            }
            selected != null -> ListeningScreen(selected, ready[selected.id] ?: 0, playback, actions) { selectedId = null }
            else -> LibraryScreen(episodes, ready, playback, resume, actions, { importDraft.open() }, { showVoices = true }, { showAbout = true }, draft.text.isNotBlank(), library.loadError)
        }
        episodes.find{it.id==coverId}?.let{episode->
            CoverPicker(episode.title,episode.id,episode.coverUrl,episode.sourceUrl,episode.coverStyle,
                onSelect={style->runCatching{library.update(episode.id){it.copy(coverStyle=style)}}.onFailure{message("封面保存失败：${it.message}")}},onDismiss={coverId=null})
        }
        deleteId?.let { id ->
            AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除这期节目？") }, text = { Text("正文、音频和进度将一起删除。") },
                confirmButton = { TextButton(onClick = {
                    if (player?.currentMediaItem?.mediaId?.startsWith("$id:") == true) { player.stop(); player.clearMediaItems() }
                    runCatching { (application as PodcastApp).deleteEpisode(id); if (selectedId == id) selectedId = null; deleteId = null }.onFailure { message(it.message ?: "删除失败") }
                }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } })
        }
        if (showAbout) AlertDialog(onDismissRequest = { showAbout = false }, title = { Text("随听 " + BuildConfig.VERSION_NAME) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("把文字、文章和 PDF，变成自己的播客。")
                Text("Edge 联网生成，正文发送至微软语音服务；系统声音由手机引擎提供。")
                Text("音频保存在本机，卸载会删除。每期最多 10 万字符，音频总量上限 512 MB。")
                Text("Edge 算法源自 rany2/edge-tts（LGPL-3.0），许可见安装包 assets/third-party。", fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } })
        if (showVoices) AlertDialog(onDismissRequest = { showVoices = false }, title = { Text("音色试听") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EdgeSpeechEngine.voices.filterKeys { it != "system" }.forEach { (key, label) ->
                    OutlinedButton(onClick = { probeVoice(key) }, enabled = voiceProbe?.running != true, modifier = Modifier.fillMaxWidth()) { Text("试听 " + label) }
                }
                HorizontalDivider()
                Text(systemVoice?.message ?: "正在检测系统中文语音…", fontSize = 13.sp)
                OutlinedButton(onClick = { probeVoice("system") }, enabled = systemVoice?.available == true && voiceProbe?.running != true) { Text("试听系统中文声音") }
                TextButton(onClick = ::checkSystemVoice) { Text("重新检测系统语音") }
                TextButton(onClick = ::openVoiceSettings) { Text("打开系统语音设置") }
            }
        }, confirmButton = { TextButton(onClick = { showVoices = false }) { Text("关闭") } })
        voiceProbe?.let { probe -> AlertDialog(onDismissRequest = { probeJob?.cancel(); voiceProbe = null }, title = { Text(voiceLabel(probe.voice)) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (probe.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(probe.message)
                if (!probe.running) TextButton(onClick = { copyVoiceReport(probe) }) { Text("复制诊断信息") }
            }
        }, confirmButton = { TextButton(onClick = { probeJob?.cancel(); voiceProbe = null }) { Text(if (probe.running) "取消" else "关闭") } }) }
    }

}

internal fun voiceLabel(voice: String) = EdgeSpeechEngine.voices[voice] ?: "历史本地音频"
internal fun statusLabel(e: Episode) = when (e.status) { "ready" -> "已保存"; "generating" -> "生成中"; "deleting" -> "正在删除…"; "failed" -> "需要处理"; "paused" -> "已停止"; else -> "待生成" }
internal fun timeLabel(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60_000, ms.coerceAtLeast(0) / 1000 % 60)
internal fun sizeLabel(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
internal fun progressFraction(e: Episode): Float {
    val total = e.segments.sumOf { it.durationMs }
    return if (total > 0) ((e.segments.take(e.segmentIndex).sumOf { it.durationMs } + e.positionMs).toFloat() / total).coerceIn(0f, 1f) else 0f
}
