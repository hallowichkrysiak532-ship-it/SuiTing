package com.suiting.podcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.PowerManager
import kotlinx.coroutines.*

class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)
    companion object {
        const val CHANNEL = "generation"; const val STOP = "stop-generation"; const val NOTIFICATION_ID = 2
        private val activeJobs = mutableMapOf<String, Job>()
        suspend fun cancelEpisode(id: String) = withContext(Dispatchers.Main.immediate) { activeJobs[id]?.cancelAndJoin() }
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "音频生成", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(title: String, progress: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, GenerationService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_podcast).setContentTitle("正在生成 · $title")
            .setContentText("已保存 $progress / $total 段").setProgress(total, progress, false)
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { if (job != null) job?.cancel() else stopSelf(startId); return START_NOT_STICKY }
        if (job != null) return START_NOT_STICKY
        val episode = intent?.getStringExtra("episode")?.let { library.find(it) }?.let { it.copy(voice = generationVoice(it.voice)) }
        if (episode == null || episode.status == "deleting") { stopSelf(startId); return START_NOT_STICKY }
        val first = notification(episode.title, library.readyCount(episode), episode.segments.size)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, first)
        job = scope.launch(start = CoroutineStart.LAZY) {
            val thisJob = currentCoroutineContext()[Job]
            try {
                acquireWakeLock()
                library.update(episode.id) { it.copy(voice = episode.voice, status = "generating", error = "") }
                val edge = episode.voice != "system"
                val workers = if (edge) EdgeBatches.WORKERS else 1
                val batches = EdgeBatches.plan(episode.segments, { library.isReady(episode, it) }, merge = edge)
                val progressLock = Any()
                fun publish(updates: Map<Int, Segment>) {
                    synchronized(progressLock) {
                        library.update(episode.id) { e -> e.copy(segments = e.segments.mapIndexed { i, s -> updates[i] ?: s }) }
                        library.find(episode.id)?.takeIf { it.status != "deleting" }?.let {
                            notifications.notify(NOTIFICATION_ID, notification(it.title, library.readyCount(it), it.segments.size))
                        }
                    }
                }
                generateSegments(batches.size, workers, engine = { speechEngine(this@GenerationService, episode.voice) }) { engine, batchIndex ->
                    currentCoroutineContext().ensureActive()
                    val indices = batches[batchIndex].indices
                    check(library.audioBytes() < Library.MAX_AUDIO_BYTES - workers * 16L * 1024 * 1024) { "音频已接近 512 MB，请先删除不需要的节目" }
                    check(filesDir.usableSpace > 64L * 1024 * 1024) { "手机剩余空间不足 64 MB，请先释放空间" }
                    if (engine is EdgeSpeechEngine && indices.size > 1) {
                        val file = library.file(episode.id, indices.first())
                        val texts = indices.map { episode.segments[it].text }
                        val words = engine.synthesizeBatch(texts, episode.voice, file)
                        currentCoroutineContext().ensureActive()
                        val slices = EdgeBatches.slices(texts, words, audioDuration(file))
                        if (slices != null) {
                            publish(indices.mapIndexed { i, index -> index to episode.segments[index].copy(
                                durationMs = slices[i].endMs - slices[i].startMs,
                                audioIndex = indices.first(), startMs = slices[i].startMs, endMs = slices[i].endMs
                            ) }.toMap())
                            return@generateSegments
                        }
                        // Unusual spoken numbers or missing word timing must never corrupt paragraph jumps.
                        file.delete()
                    }
                    for (index in indices) {
                        currentCoroutineContext().ensureActive()
                        val file = library.file(episode.id, index)
                        engine.synthesize(episode.segments[index].text, episode.voice, file)
                        currentCoroutineContext().ensureActive()
                        publish(mapOf(index to episode.segments[index].copy(durationMs = audioDuration(file), audioIndex = null, startMs = 0, endMs = null)))
                    }
                }
                library.update(episode.id) { it.copy(status = "ready", error = "") }
            } catch (e: TimeoutCancellationException) {
                runCatching { library.update(episode.id) { it.copy(status = "failed", error = "语音生成超时，请检查网络或中文语音包后重试") } }
            } catch (e: CancellationException) {
                runCatching { library.update(episode.id) { it.copy(status = "paused", error = "已停止，已完成的段落会保留") } }
            } catch (e: Exception) {
                runCatching { library.update(episode.id) { it.copy(status = "failed", error = e.message ?: "生成失败，请重试") } }
            } finally {
                releaseWakeLock()
                withContext(NonCancellable + Dispatchers.Main) {
                    if (activeJobs[episode.id] === thisJob) activeJobs.remove(episode.id)
                    job = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        val scheduledJob = job!!
        activeJobs[episode.id] = scheduledJob
        // Also clean up if cancellation happens before the lazy coroutine enters its body.
        scheduledJob.invokeOnCompletion {
            Handler(mainLooper).post {
                if (activeJobs[episode.id] === scheduledJob) activeJobs.remove(episode.id)
                if (job === scheduledJob) { job = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        scheduledJob.start()
        return START_NOT_STICKY
    }
    private fun audioDuration(file: java.io.File): Long = try {
        check(file.length() <= 8L * 1024 * 1024) { "单段音频超过 8 MB，请拆短正文后重试" }
        MediaMetadataRetriever().let { retriever ->
            try { retriever.setDataSource(file.path); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
            finally { retriever.release() }
        }.also { check(it > 0) { "语音文件无法读取，请重试" } }
    } catch (e: Exception) { file.delete(); throw e }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
    @Synchronized private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SuiTing:AudioGeneration").apply {
            setReferenceCounted(false)
            // Safety cap matches Android's dataSync service budget; normal completion releases immediately.
            acquire(6 * 60 * 60 * 1000L)
        }
    }
    @Synchronized private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onDestroy() { scope.cancel(); releaseWakeLock(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
