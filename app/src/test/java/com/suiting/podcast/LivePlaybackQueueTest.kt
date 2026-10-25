package com.suiting.podcast

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

class LivePlaybackQueueTest {
    private val id = "episode"
    private val player = mock(Player::class.java)
    private fun item(index: Int) = MediaItem.Builder().setMediaId("$id:$index").build()

    private fun playing(state: Int = Player.STATE_READY, playWhenReady: Boolean = true) {
        `when`(player.currentMediaItem).thenReturn(item(0))
        `when`(player.mediaItemCount).thenReturn(1)
        `when`(player.playbackState).thenReturn(state)
        `when`(player.playWhenReady).thenReturn(playWhenReady)
    }

    @Test fun downloadDuringPlaybackAppendsWithoutSeekingOrResetting() {
        playing()
        appendReadyAudio(player, id, 3, ::item)
        verify(player).addMediaItems(listOf(item(1), item(2)))
        verify(player, never()).seekTo(anyInt(), anyLong())
        verify(player, never()).prepare()
        verify(player, never()).setPlayWhenReady(anyBoolean())
    }

    @Test fun downloadAfterBoundaryContinuesAtTheFirstNewParagraph() {
        playing(Player.STATE_ENDED)
        appendReadyAudio(player, id, 3, ::item)
        verify(player).seekTo(1, 0)
        verify(player).prepare()
        verify(player).setPlayWhenReady(true)
    }

    @Test fun downloadingNeverResumesAnExplicitlyPausedPlayer() {
        playing(Player.STATE_ENDED, false)
        appendReadyAudio(player, id, 2, ::item)
        verify(player).addMediaItems(listOf(item(1)))
        verify(player).setPlayWhenReady(false)
        verify(player, never()).play()
    }

    @Test fun downloadForAnotherEpisodeDoesNotChangePlayback() {
        playing()
        clearInvocations(player)
        appendReadyAudio(player, "another-episode", 5) { error("must not build unrelated items") }
        verify(player, never()).addMediaItems(anyList())
        verify(player, never()).seekTo(anyInt(), anyLong())
    }

    @Test fun repeatedLibraryProgressDoesNotAppendDuplicates() {
        playing()
        var count = 1
        `when`(player.mediaItemCount).thenAnswer { count }
        doAnswer { count += (it.arguments[0] as List<*>).size; null }.`when`(player).addMediaItems(anyList())
        repeat(4) { appendReadyAudio(player, id, 3, ::item) }
        assertEquals(3, count)
        verify(player, times(1)).addMediaItems(listOf(item(1), item(2)))
    }

    @Test fun paragraphMediaItemsUseTheExactPersistedClipBoundaries() {
        val store = object : SnapshotStore { override fun read(): String? = null; override fun write(raw: String) {} }
        val episode = Episode(title = "共享音频", voice = "system", segments = listOf(
            Segment("第一段", 1000, 0, 0, 1000), Segment("第二段", 2000, 0, 1000, 3000)))
        val base = java.nio.file.Files.createTempDirectory("clip-test").toFile()
        try {
            val library = Library(base, store)
            val second = episodeMediaItem(library, episode, 1)
            assertEquals("${episode.id}:1", second.mediaId)
            assertEquals(1000L, second.clippingConfiguration.startPositionMs)
            assertEquals(3000L, second.clippingConfiguration.endPositionMs)
        } finally { base.deleteRecursively() }
    }
}
