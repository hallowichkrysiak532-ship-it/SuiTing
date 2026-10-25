package com.suiting.podcast

import androidx.media3.common.Player
import kotlin.math.roundToLong

internal data class PlaybackUi(
    val episodeId: String? = null, val index: Int = 0, val positionMs: Long = 0,
    val playWhenReady: Boolean = false, val state: Int = Player.STATE_IDLE, val speed: Float = 1f
) {
    fun requested(e: Episode, ready: Int) = episodeId == e.id && playWhenReady &&
        (state != Player.STATE_ENDED || (ready < e.segments.size && e.status == "generating"))
    fun index(e: Episode, ready: Int) = (if (episodeId == e.id) index else e.segmentIndex).coerceIn(0, (ready - 1).coerceAtLeast(0))
    fun position(e: Episode) = if (episodeId == e.id) positionMs else e.positionMs
}

internal fun playbackUi(player: Player?): PlaybackUi {
    val id = player?.currentMediaItem?.mediaId?.takeIf { ':' in it } ?: return PlaybackUi(speed = player?.playbackParameters?.speed ?: 1f)
    return PlaybackUi(id.substringBefore(':'), id.substringAfter(':').toIntOrNull() ?: 0,
        player.currentPosition.coerceAtLeast(0), player.playWhenReady, player.playbackState, player.playbackParameters.speed)
}

internal data class ParagraphPosition(val index: Int, val positionMs: Long)

/** Unknown paragraphs get an explicit estimate; only the verified prefix can be sought. */
internal class EpisodeTimeline(val episode: Episode, readyCount: Int) {
    val ready = readyCount.coerceIn(0, episode.segments.size)
    private fun estimate(text: String): Long {
        val chinese = text.count { it.code in 0x3400..0x9fff }
        val words = Regex("[A-Za-z0-9]+(?:['’][A-Za-z]+)?").findAll(text).count()
        return (chinese * 235L + words * 380L + text.count { it in "。！？.!?；;" } * 180L).coerceAtLeast(1200)
    }
    private val durations = episode.segments.map { if (it.durationMs > 0) it.durationMs else estimate(it.text) }
    val totalMs = durations.sum().coerceAtLeast(1)
    val availableMs = durations.take(ready).sum()
    val estimated = ready < episode.segments.size
    val availableFraction get() = availableMs.toFloat() / totalMs
    fun elapsed(index: Int, position: Long): Long = if (ready == 0) 0 else {
        val i = index.coerceIn(0, ready - 1)
        (durations.take(i).sum() + position.coerceIn(0, durations[i])).coerceIn(0, availableMs)
    }
    fun fraction(index: Int, position: Long) = elapsed(index, position).toFloat() / totalMs
    fun seekTo(milliseconds: Long): ParagraphPosition? {
        if (ready == 0) return null
        var remaining = milliseconds.coerceIn(0, availableMs)
        for (index in 0 until ready) {
            if (remaining < durations[index] || index == ready - 1) return ParagraphPosition(index, remaining.coerceAtMost(durations[index]))
            remaining -= durations[index]
        }
        return null
    }
    fun seekFraction(fraction: Float): ParagraphPosition? = seekTo((fraction.coerceIn(0f, 1f) * totalMs).roundToLong())
    fun finished(): Boolean = ready == episode.segments.size && episode.segmentIndex == episode.segments.lastIndex &&
        episode.positionMs >= durations.last() - minOf(500L,durations.last()/20)
}

internal enum class LibraryFilter(val label: String) { ALL("全部"), UNFINISHED("未听完"), DOWNLOADED("已下载"), GENERATING("生成中") }
internal fun LibraryFilter.matches(e: Episode, ready: Int) = when (this) {
    LibraryFilter.ALL -> true
    LibraryFilter.UNFINISHED -> ready > 0 && !EpisodeTimeline(e, ready).finished()
    LibraryFilter.DOWNLOADED -> ready == e.segments.size
    LibraryFilter.GENERATING -> e.status == "generating"
}
