package com.suiting.podcast

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import java.io.File

class PodcastApp : Application() {
    lateinit var library: Library
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() { super.onCreate(); library = Library(this) }
    fun deleteEpisode(id: String) {
        library.beginDelete(id)
        tasks.launch {
            try {
                GenerationService.cancelEpisode(id)
                withContext(Dispatchers.IO) { library.delete(id) }
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { library.deletionFailed(id, e.message ?: "删除失败，请重试") }
            }
        }
    }
}

val Context.library: Library get() = (applicationContext as PodcastApp).library

internal interface SnapshotStore { fun read(): String?; fun write(raw: String) }

private class AtomicSnapshot(file: File) : SnapshotStore {
    private val manifest = AtomicFile(file)
    override fun read(): String? = if (manifest.baseFile.exists() || File(manifest.baseFile.path + ".bak").exists())
        manifest.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } else null
    override fun write(raw: String) {
        val stream = manifest.startWrite()
        try { stream.write(raw.toByteArray(Charsets.UTF_8)); manifest.finishWrite(stream) }
        catch (e: Exception) { manifest.failWrite(stream); throw e }
    }
}

class Library internal constructor(private val base: File, private val store: SnapshotStore) {
    constructor(context: Context) : this(File(context.filesDir, "podcasts"), AtomicSnapshot(File(context.filesDir, "podcasts/library.json")))
    private val mutable = MutableStateFlow<List<Episode>>(emptyList())
    val episodes = mutable.asStateFlow()
    var loadError: String? = null
        private set
    companion object { const val MAX_AUDIO_BYTES = 512L * 1024 * 1024 }

    init {
        base.mkdirs()
        try {
            mutable.value = store.read()?.let { EpisodeCodec.decode(it) }.orEmpty().map {
                when (it.status) {
                    "generating" -> it.copy(status = "paused", error = "上次生成中断，可以继续")
                    "deleting" -> it.copy(status = "failed", error = "上次删除中断，请重新点删除")
                    else -> it
                }
            }
        } catch (e: Exception) { loadError = "节目库读取失败，已保留原文件：${e.message}" }
    }

    fun file(id: String, index: Int): File {
        require(id.matches(Regex("[a-fA-F0-9-]{36}")))
        require(index >= 0)
        return File(File(base, id), "%04d.audio".format(index))
    }
    fun readyCount(e: Episode): Int = e.segments.indices.takeWhile { file(e.id, it).let { f -> f.exists() && f.length() > 0 } }.count()
    fun audioBytes(): Long = base.walkTopDown().filter { it.isFile && it.extension in setOf("audio", "part", "request") }.sumOf { it.length() }
    fun episodeBytes(id: String): Long = File(base, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun find(id: String): Episode? = mutable.value.find { it.id == id }

    @Synchronized fun add(e: Episode) { check(loadError == null) { loadError!! }; commit(listOf(e) + mutable.value) }
    @Synchronized fun update(id: String, change: (Episode) -> Episode) {
        val existing = find(id) ?: return
        if (existing.status == "deleting") return
        commit(mutable.value.map { if (it.id == id) change(it) else it })
    }
    @Synchronized fun progress(id: String, index: Int, position: Long) {
        val e = find(id) ?: return
        if (e.status == "deleting") return
        val safeIndex = index.coerceIn(0, e.segments.lastIndex)
        val duration = e.segments[safeIndex].durationMs
        val safePosition = position.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        if (e.segmentIndex != safeIndex || e.positionMs != safePosition) update(id) { it.copy(segmentIndex = safeIndex, positionMs = safePosition) }
    }
    @Synchronized fun beginDelete(id: String) {
        commit(mutable.value.map { if (it.id == id) it.copy(status = "deleting", error = "") else it })
    }
    @Synchronized fun deletionFailed(id: String, error: String) {
        val items = mutable.value.map { if (it.id == id) it.copy(status = "failed", error = error) else it }
        // A full disk must not leave the UI permanently stuck in "deleting".
        try { commit(items) } catch (_: Exception) { mutable.value = items }
    }
    @Synchronized fun delete(id: String) {
        val e = find(id) ?: return
        check(e.status == "deleting") { "请先取消生成后再删除" }
        val directory = file(id, 0).parentFile!!
        check(directory.deleteRecursively() || !directory.exists()) { "音频文件删除失败，请重试" }
        commit(mutable.value.filterNot { it.id == id })
    }
    private fun commit(items: List<Episode>) {
        check(loadError == null) { loadError!! }
        store.write(EpisodeCodec.encode(items))
        mutable.value = items
    }
}
