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
import kotlinx.coroutines.*

class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var currentId: String? = null
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
        val episode = intent?.getStringExtra("episode")?.let { library.find(it) }
        if (episode == null || episode.status == "deleting") { stopSelf(startId); return START_NOT_STICKY }
        currentId = episode.id
        val first = notification(episode.title, library.readyCount(episode), episode.segments.size)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, first)
        job = scope.launch(start = CoroutineStart.LAZY) {
            val thisJob = currentCoroutineContext()[Job]
            var engine: SpeechEngine? = null
            try {
                library.update(episode.id) { it.copy(status = "generating", error = "") }
                engine = if (episode.voice == "system") SystemSpeechEngine(this@GenerationService) else EdgeSpeechEngine()
                for (index in episode.segments.indices) {
                    ensureActive()
                    val file = library.file(episode.id, index)
                    if (!file.exists()) {
                        check(library.audioBytes() < Library.MAX_AUDIO_BYTES - 8L * 1024 * 1024) { "音频已接近 512 MB，请先删除不需要的节目" }
                        check(filesDir.usableSpace > 64L * 1024 * 1024) { "手机剩余空间不足 64 MB，请先释放空间" }
                        engine.synthesize(episode.segments[index].text, episode.voice, file)
                    }
                    ensureActive()
                    val duration = try {
                        check(file.length() <= 8L * 1024 * 1024) { "单段音频超过 8 MB，请拆短正文后重试" }
                        MediaMetadataRetriever().let { retriever ->
                            try { retriever.setDataSource(file.path); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
                            finally { retriever.release() }
                        }.also { check(it > 0) { "语音文件无法读取，请重试" } }
                    } catch (e: Exception) { file.delete(); throw e }
                    library.update(episode.id) { e -> e.copy(segments = e.segments.mapIndexed { i, s -> if (i == index) s.copy(durationMs = duration) else s }) }
                    notifications.notify(NOTIFICATION_ID, notification(episode.title, index + 1, episode.segments.size))
                }
                library.update(episode.id) { it.copy(status = "ready", error = "") }
            } catch (e: TimeoutCancellationException) {
                runCatching { library.update(episode.id) { it.copy(status = "failed", error = "语音生成超时，请检查网络或中文语音包后重试") } }
            } catch (e: CancellationException) {
                runCatching { library.update(episode.id) { it.copy(status = "paused", error = "已停止，已完成的段落会保留") } }
            } catch (e: Exception) {
                runCatching { library.update(episode.id) { it.copy(status = "failed", error = e.message ?: "生成失败，请重试") } }
            } finally {
                engine?.close()
                currentId = null
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
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
