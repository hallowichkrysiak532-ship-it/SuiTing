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

private val Green = Color(0xFF193E35)
private val Cream = Color(0xFFF6F3E9)
private val Gold = Color(0xFFD9AF63)
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
        receiveShare(intent)
        checkSystemVoice()
        controllerFuture.addListener({
            if (!isDestroyed) runCatching { controller = controllerFuture.get() }.onFailure { message("播放器连接失败：${it.message}") }
        }, ContextCompat.getMainExecutor(this))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, onPrimary = Color.White, secondary = Gold, background = Cream, surface = Cream)) {
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
    private fun probeVoice(voice: String) {
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
                    engine = if (voice == "system") SystemSpeechEngine(this@MainActivity) else EdgeSpeechEngine()
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
    private fun playEpisode(episode: Episode, paragraph: Int? = null) {
        if (library.find(episode.id)?.status == "deleting") return
        val player = controller ?: run { message("播放器正在连接，请稍后再试"); return }
        val count = library.readyCount(episode)
        if (count == 0) return
        val sameEpisode = player.currentMediaItem?.mediaId?.startsWith(episode.id + ":") == true
        val index = (paragraph ?: episode.segmentIndex).coerceIn(0, count - 1)
        val position = if (paragraph == null) episode.positionMs else 0L
        val media = (0 until count).map { i ->
            MediaItem.Builder().setMediaId("${episode.id}:$i").setUri(android.net.Uri.fromFile(library.file(episode.id, i)))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(episode.title).setArtist("随听 · 第 ${i + 1} / ${episode.segments.size} 段").setAlbumTitle(episode.title).build()).build()
        }
        // Rebuild the queue when newly generated paragraphs become available.
        if (!sameEpisode || player.mediaItemCount != count) { player.setMediaItems(media, index, position); player.prepare() }
        else if (paragraph != null) player.seekTo(index, 0)
        else if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
        player.play()
    }

    @Composable private fun PodcastScreen() {
        val episodes by library.episodes.collectAsStateWithLifecycle()
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        val showCreate by importDraft.show.collectAsStateWithLifecycle()
        val draft by importDraft.draft.collectAsStateWithLifecycle()
        var deleteId by remember { mutableStateOf<String?>(null) }
        var showAbout by remember { mutableStateOf(false) }
        var showVoices by remember { mutableStateOf(false) }
        var tick by remember { mutableIntStateOf(0) }
        val selected = episodes.find { it.id == selectedId }
        val player = controller
        val bytes = remember(episodes) { library.audioBytes() }
        LaunchedEffect(player) { while (true) { delay(500); tick++ } }
        DisposableEffect(player) {
            val listener = object : Player.Listener { override fun onPlayerError(error: PlaybackException) { message("播放失败：${error.localizedMessage}") } }
            player?.addListener(listener)
            onDispose { player?.removeListener(listener) }
        }
        Scaffold(containerColor = Cream, contentWindowInsets = WindowInsets.safeDrawing,
            floatingActionButton = { if (selected == null) FloatingActionButton(onClick = { importDraft.open() }, containerColor = Green, contentColor = Color.White) { Text(if (draft.text.isBlank()) "＋ 添加" else "编辑草稿", Modifier.padding(horizontal = 14.dp)) } }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(22.dp, 18.dp, 22.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        if (selected != null) TextButton(onClick = { selectedId = null }) { Text("‹ 节目库") }
                        else Column(Modifier.weight(1f)) { Text("随听", fontSize = 36.sp, color = Green, fontWeight = FontWeight.Bold); Text("把想读的，留给耳朵", color = Green.copy(alpha = .65f)) }
                        if (selected != null) Spacer(Modifier.weight(1f))
                        if (selected != null) TextButton(onClick = { deleteId = selected.id }, enabled = selected.status != "deleting") { Text("删除", color = MaterialTheme.colorScheme.error) }
                        else TextButton(onClick = { showVoices = true }) { Text("音色试听") }
                        TextButton(onClick = { showAbout = true }) { Text("关于") }
                    }
                }
                if (library.loadError != null) item { Text(library.loadError!!, color = MaterialTheme.colorScheme.error) }
                if (selected == null) {
                    item {
                        Surface(color = Green, shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("我的声音书架", color = Gold, fontSize = 14.sp)
                                Text("一段文字，一期自己的节目。", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                                Text("生成后保存在手机 · 随时继续听", color = Color.White.copy(alpha = .75f), fontSize = 13.sp)
                            }
                        }
                    }
                    item { Text("${episodes.size} 期节目  ·  音频 ${sizeLabel(bytes)} / 512 MB", color = Green.copy(alpha = .65f), fontSize = 13.sp) }
                    if (episodes.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("从第一段文字开始", color = Green, fontSize = 21.sp)
                            Spacer(Modifier.height(8.dp)); Text("添加正文，选一个声音，再生成音频。", color = Green.copy(alpha = .6f))
                        }
                    }
                    itemsIndexed(episodes, key = { _, e -> e.id }) { _, e ->
                        ElevatedCard(onClick = { selectedId = e.id }, colors = CardDefaults.elevatedCardColors(containerColor = Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(e.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Green, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { deleteId = e.id }, enabled = e.status != "deleting") { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                                Text("${voiceLabel(e.voice)} · ${statusLabel(e)}", color = Green.copy(alpha = .65f), fontSize = 13.sp)
                                val ready = library.readyCount(e)
                                LinearProgressIndicator(progress = { if (e.status == "generating") ready.toFloat() / e.segments.size else progressFraction(e) }, modifier = Modifier.fillMaxWidth(), color = Gold, trackColor = Cream)
                                Text(if (ready > 0) "${ready}/${e.segments.size} 段已保存 · 听到第 ${e.segmentIndex + 1} 段 ${timeLabel(e.positionMs)}" else "等待生成音频", color = Green.copy(alpha = .65f), fontSize = 12.sp)
                                if (e.error.isNotEmpty()) Text(e.error, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    val e = selected
                    val ready = library.readyCount(e)
                    item {
                        Text(e.title, fontSize = 30.sp, color = Green, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("${voiceLabel(e.voice)} · ${statusLabel(e)} · ${sizeLabel(library.episodeBytes(e.id))}", color = Green.copy(alpha = .65f), fontSize = 13.sp)
                    }
                    if (e.error.isNotEmpty()) item { Surface(color = Gold.copy(alpha = .2f), shape = RoundedCornerShape(12.dp)) { Text(e.error, Modifier.padding(14.dp), color = Green, fontSize = 14.sp) } }
                    if (e.status != "deleting") item { TextButton(onClick = { probeVoice(e.voice) }, enabled = voiceProbe?.running != true) { Text("试听音色") } }
                    if (e.status == "generating") item {
                        LinearProgressIndicator(progress = { ready.toFloat() / e.segments.size }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { startService(Intent(this@MainActivity, GenerationService::class.java).setAction(GenerationService.STOP)) }) { Text("停止生成") }
                    } else if (e.status != "deleting" && ready < e.segments.size) item {
                        Button(onClick = { generate(e) }, modifier = Modifier.fillMaxWidth()) { Text(if (ready > 0) "继续生成剩余段落" else "生成音频") }
                    }
                    if (ready > 0 && e.status != "deleting") item { PlayerPanel(e, ready, player, tick) }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("段落目录", color = Green, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${e.segments.size} 段", color = Green.copy(alpha = .6f), fontSize = 13.sp)
                        }
                    }
                    itemsIndexed(e.segments, key = { i, _ -> i }) { i, s ->
                        val active = player?.currentMediaItem?.mediaId == "${e.id}:$i" || (player?.currentMediaItem == null && e.segmentIndex == i)
                        Surface(color = if (active) Green.copy(alpha = .09f) else Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.clickable(enabled = i < ready && e.status != "deleting") { playEpisode(e, i) }) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${if (active) "▸ " else ""}第 ${i + 1} 段  ·  ${if (i < ready) timeLabel(s.durationMs) else "尚未生成"}", color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(s.text, color = Green.copy(alpha = if (i < ready) .9f else .5f), fontSize = 16.sp, lineHeight = 25.sp)
                            }
                        }
                    }
                }
            }
        }
        if (showCreate) CreateDialog(importDraft, systemVoice, ::probeVoice, ::openVoiceSettings, onDismiss = { importDraft.close() }) { title, text, voice, parts ->
            runCatching {
                val e = Episode(title = title.ifBlank { text.trim().take(24) }, voice = voice, segments = parts, sourceText = text)
                library.add(e); selectedId = e.id; importDraft.clear(); generate(e)
            }.onFailure { message(it.message ?: "创建失败") }
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

    @Composable private fun PlayerPanel(e: Episode, ready: Int, player: MediaController?, tick: Int) {
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        val same = player?.currentMediaItem?.mediaId?.startsWith(e.id + ":") == true
        val index = if (same) player!!.currentMediaItemIndex.coerceIn(0, ready - 1) else e.segmentIndex.coerceIn(0, ready - 1)
        val duration = e.segments[index].durationMs.coerceAtLeast(1)
        val position = if (same) player!!.currentPosition.coerceIn(0, duration) else e.positionMs.coerceIn(0, duration)
        var dragging by remember(e.id, index) { mutableStateOf<Float?>(null) }
        Surface(color = Green, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("正在收听  ·  第 ${index + 1} / ${e.segments.size} 段", color = Gold, fontSize = 13.sp)
                Text(e.segments[index].text, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 25.sp)
                Slider(value = dragging ?: (position.toFloat() / duration), onValueChange = { dragging = it }, onValueChangeFinished = {
                    val target = ((dragging ?: 0f) * duration).toLong()
                    if (!same) playEpisode(e, index)
                    player?.seekTo(index, target); dragging = null
                }, colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color.White.copy(alpha = .2f)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timeLabel((dragging?.let { it * duration }?.toLong()) ?: position), color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                    Text(timeLabel(duration), color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { playEpisode(e, (index - 1).coerceAtLeast(0)) }, enabled = index > 0) { Text("上段", color = if (index > 0) Color.White else Color.Gray) }
                    Button(onClick = { if (same && player?.isPlaying == true) player.pause() else playEpisode(e) }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Green)) { Text(if (same && player?.isPlaying == true) "暂停" else "播放", fontSize = 18.sp) }
                    TextButton(onClick = { playEpisode(e, index + 1) }, enabled = index + 1 < ready) { Text("下段", color = if (index + 1 < ready) Color.White else Color.Gray) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { if (!same) playEpisode(e); player?.seekTo((player.currentPosition - 15_000).coerceAtLeast(0)) }) { Text("−15 秒", color = Color.White) }
                    TextButton(onClick = {
                        val speeds = listOf(.8f, 1f, 1.25f, 1.5f, 2f)
                        val current = player?.playbackParameters?.speed ?: 1f
                        player?.setPlaybackSpeed(speeds[(speeds.indexOf(current) + 1) % speeds.size])
                    }) { Text("${player?.playbackParameters?.speed ?: 1f}×", color = Gold) }
                    TextButton(onClick = { if (!same) playEpisode(e); player?.seekTo((player.currentPosition + 15_000).coerceAtMost(duration)) }) { Text("＋15 秒", color = Color.White) }
                }
                if (ready < e.segments.size) Text("当前可听前 $ready 段；其余段落生成后可继续。", color = Gold, fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun CreateDialog(
    model: ImportDraftModel, systemVoice: SystemVoiceStatus?, onProbe: (String) -> Unit, onSettings: () -> Unit,
    onDismiss: () -> Unit, onCreate: (String, String, String, List<Segment>) -> Unit
) {
    val draft by model.draft.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val pending by model.pending.collectAsStateWithLifecycle()
    val text = draft.text
    var voice by rememberSaveable { mutableStateOf("zh-CN-XiaoxiaoNeural") }
    var keepLines by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    var linkEdited by rememberSaveable { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var browser by remember { mutableStateOf<String?>(null) }
    val split by produceState<Result<List<Segment>>?>(null, text, keepLines) {
        value = null
        delay(250)
        value = withContext(Dispatchers.Default) {
            runCatching { TextSegments.split(text, mode = if (keepLines) TextSegments.Mode.KEEP_LINES else TextSegments.Mode.AUTO) }
        }
    }
    val parts = split?.getOrNull()
    val onlyLink = Regex("^https?://\\S+\\s*$", RegexOption.IGNORE_CASE).matches(text.trim())
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.file(uri)
    }
    LaunchedEffect(draft.source, text.takeIf { onlyLink }, draft.originalText.takeIf { it.length < 2000 }) {
        if (linkEdited) return@LaunchedEffect
        if (onlyLink) link = text.trim()
        else if (draft.source.startsWith("https://")) link = draft.source
        else {
            val raw = draft.originalText.ifBlank { text }
            if (raw.length < 2000) Regex("https?://\\S+").find(raw)?.value?.let { link = it }
        }
    }
    LaunchedEffect(systemVoice) { if (voice == "system" && systemVoice?.available == false) voice = "zh-CN-XiaoxiaoNeural" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加节目") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(link, { link = it; linkEdited = true }, label = { Text("公众号或网页链接") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                    runCatching { ArticleHtml.url(link).toString() }.onSuccess { browser = it }
                        .onFailure { model.error.value = it.message ?: "链接无效" }
                }, enabled = link.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("打开页面提取") }
            OutlinedButton(onClick = { pdf.launch(arrayOf("application/pdf")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入 PDF 全文") }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在提取…", fontSize = 12.sp)
                TextButton(onClick = { model.cancel() }) { Text("取消导入") }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            HorizontalDivider()
            OutlinedTextField(draft.title, model::title, label = { Text("标题（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { model.clipboard() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("粘贴正文") }
            LongTextEditor(text, model::text, Modifier.fillMaxWidth().heightIn(min = 170.dp, max = 260.dp))
            Text(text.length.toString() + " / " + TextSegments.MAX_INPUT + " 字符",
                color = if (text.length > TextSegments.MAX_INPUT) MaterialTheme.colorScheme.error else Green, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { model.cleanText() }, enabled = !busy && text.isNotBlank()) { Text("清理网址和乱码") }
                if (draft.originalText.isNotBlank() && draft.originalText != text) TextButton(onClick = { restoring = true }, enabled = !busy) { Text("恢复原稿") }
            }
            if (draft.notice.contains("20000 字符")) Text("剪贴板仅提供了 20000 字符，可改用链接或 PDF 导入全文。", fontSize = 12.sp)
            if (onlyLink) Text("正文目前只有链接，请先在上方打开页面提取文章。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Text("分段方式", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().clickable { keepLines = false }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !keepLines, onClick = { keepLines = false }); Text("自动分段", fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth().clickable { keepLines = true }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = keepLines, onClick = { keepLines = true }); Text("保留每行", fontSize = 13.sp)
            }
            if (parts != null && !onlyLink) OutlinedButton(onClick = { preview = true }) { Text("预览分段（" + parts.size + " 段）") }
            else if (split?.isFailure == true && text.isNotBlank()) Text(split?.exceptionOrNull()?.message ?: "请检查正文", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            else if (split == null && text.isNotBlank()) Text("正在整理分段…", fontSize = 12.sp)
            Text("选择声音", fontWeight = FontWeight.SemiBold)
            EdgeSpeechEngine.voices.forEach { (key, label) ->
                val available = key != "system" || systemVoice?.available == true
                Row(Modifier.fillMaxWidth().clickable(enabled = available) { voice = key }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = key == voice, onClick = { voice = key }, enabled = available)
                    Text(label + if (!available) "（不可用）" else "", fontSize = 14.sp, color = if (available) Green else Color.Gray)
                }
            }
            if (voice == "system") Text(systemVoice?.message ?: "正在检测系统声音…", fontSize = 12.sp)
            if (systemVoice?.available == false) TextButton(onClick = onSettings) { Text("系统语音设置") }
            OutlinedButton(onClick = { onProbe(voice) }) { Text("试听音色") }
        }
    }, confirmButton = {
        Button(onClick = { onCreate(draft.title.trim(), text, voice, parts!!) },
            enabled = parts != null && !onlyLink && !busy && text.length <= TextSegments.MAX_INPUT && (voice != "system" || systemVoice?.available == true)) { Text("创建并生成") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("暂存关闭") } })
    if (preview && parts != null) AlertDialog(onDismissRequest = { preview = false }, title = { Text("分段预览 · " + parts.size + " 段") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(parts) { index, part ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("第 " + (index + 1) + " 段 · " + part.text.length + " 字符", color = Green, fontWeight = FontWeight.SemiBold)
                        Text(part.text, fontSize = 14.sp, lineHeight = 22.sp); HorizontalDivider()
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { preview = false }) { Text("返回编辑") } })
    pending?.let { incoming -> AlertDialog(onDismissRequest = { model.pending.value = null }, title = { Text("替换当前正文？") },
        text = { Text("当前草稿 " + text.length + " 字符；新导入 " + incoming.text.length + " 字符。取消会保留当前草稿。") },
        confirmButton = { TextButton(onClick = { model.accept() }) { Text("使用导入内容") } },
        dismissButton = { TextButton(onClick = { model.pending.value = null }) { Text("保留当前正文") } }) }
    browser?.let { address -> WebArticleDialog(address, onDismiss = { browser = null }, onExtract = { model.offer(it); browser = null }) }
    if (restoring) AlertDialog(onDismissRequest = { restoring = false }, title = { Text("恢复导入时的原稿？") },
        text = { Text("将替换当前修改。") },
        confirmButton = { TextButton(onClick = { model.restoreOriginal(); restoring = false }) { Text("恢复原稿") } },
        dismissButton = { TextButton(onClick = { restoring = false }) { Text("取消") } })
}

private fun voiceLabel(voice: String) = EdgeSpeechEngine.voices[voice] ?: voice
private fun statusLabel(e: Episode) = when (e.status) { "ready" -> "已保存"; "generating" -> "生成中"; "deleting" -> "正在删除…"; "failed" -> "需要处理"; "paused" -> "已停止"; else -> "待生成" }
private fun timeLabel(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60_000, ms.coerceAtLeast(0) / 1000 % 60)
private fun sizeLabel(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
private fun progressFraction(e: Episode): Float {
    val total = e.segments.sumOf { it.durationMs }
    return if (total > 0) ((e.segments.take(e.segmentIndex).sumOf { it.durationMs } + e.positionMs).toFloat() / total).coerceIn(0f, 1f) else 0f
}
