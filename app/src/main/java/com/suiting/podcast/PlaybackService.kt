package com.suiting.podcast

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*

class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { persist() }
                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    save(oldPosition.mediaItem?.mediaId, oldPosition.positionMs)
                    persist()
                }
                override fun onPlaybackStateChanged(playbackState: Int) { persist() }
            })
        }
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                // Own UI and trusted system media controllers can control this private library.
                return if (controller.isTrusted || controller.packageName == packageName) super.onConnect(session, controller)
                else MediaSession.ConnectionResult.reject()
            }
        }).build()
        scope.launch { while (isActive) { delay(2000); if (player.isPlaying) persist() } }
    }
    private fun save(mediaId: String?, position: Long) {
        val parts = mediaId?.split(":") ?: return
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return
        runCatching { library.progress(parts[0], index, position) }
    }
    private fun persist() { if (::player.isInitialized) save(player.currentMediaItem?.mediaId, player.currentPosition) }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onTaskRemoved(rootIntent: Intent?) { persist(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { persist(); scope.cancel(); session.release(); player.release(); super.onDestroy() }
}
