package com.suiting.podcast

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player

internal fun episodeMediaItem(library: Library, episode: Episode, index: Int): MediaItem =
    MediaItem.Builder().setMediaId("${episode.id}:$index").setUri(library.audioFile(episode, index).toURI().toString())
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(episode.segments[index].startMs)
            .apply { episode.segments[index].endMs?.let { setEndPositionMs(it) } }.build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(episode.title)
            .setArtist("随听 · 第 ${index + 1} / ${episode.segments.size} 段").setAlbumTitle(episode.title).build()).build()

/** Runs in the playback service, including while the activity is in the background. */
internal fun appendReadyAudio(player: Player, episodeId: String, ready: Int, item: (Int) -> MediaItem) {
    if (player.currentMediaItem?.mediaId?.startsWith("$episodeId:") != true) return
    val previousCount = player.mediaItemCount
    if (previousCount == 0 || ready <= previousCount) return
    val wasEnded = player.playbackState == Player.STATE_ENDED
    val resume = player.playWhenReady
    player.addMediaItems((previousCount until ready).map(item))
    // At the old download boundary, continue with the first new paragraph, never replay the old one.
    if (wasEnded) {
        player.seekTo(previousCount, 0)
        player.prepare()
        player.playWhenReady = resume
    }
}
