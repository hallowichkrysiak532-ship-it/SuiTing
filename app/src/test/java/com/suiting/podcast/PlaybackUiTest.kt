package com.suiting.podcast

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackUiTest {
    private fun episode()=Episode(title="测试",voice="system",segments=listOf(Segment("甲",1000),Segment("乙",2000),Segment("丙",3000)))
    @Test fun seekingNeverEntersAnUndownloadedParagraph() {
        val timeline=EpisodeTimeline(episode(),2)
        assertEquals(6000L,timeline.totalMs)
        assertEquals(3000L,timeline.availableMs)
        assertEquals(ParagraphPosition(1,2000),timeline.seekTo(5900))
        assertEquals(ParagraphPosition(1,2000),timeline.seekFraction(1f))
        assertNull(EpisodeTimeline(episode(),0).seekTo(1000))
    }
    @Test fun seekAndElapsedAreRelativeToEachParagraphIncludingSharedClips() {
        val e=episode().copy(segments=listOf(Segment("甲",1000,0,0,1000),Segment("乙",2000,0,1000,3000),Segment("丙",3000)))
        val timeline=EpisodeTimeline(e,3)
        assertEquals(ParagraphPosition(1,0),timeline.seekTo(1000))
        assertEquals(ParagraphPosition(2,1500),timeline.seekTo(4500))
        assertEquals(4500L,timeline.elapsed(2,1500))
        assertEquals(ParagraphPosition(0,0),timeline.seekTo(-15000))
        assertEquals(.25f,timeline.fraction(1,500),.0001f)
    }
    @Test fun unknownAudioIsShownAsAnEstimateAndBecomesExactOnCompletion() {
        val e=episode().copy(segments=listOf(Segment("已生成",3000),Segment("剩下的这段还没有生成")))
        val partial=EpisodeTimeline(e,1)
        assertTrue(partial.estimated)
        assertTrue(partial.totalMs>partial.availableMs)
        assertEquals(ParagraphPosition(0,3000),partial.seekFraction(1f))
        val complete=EpisodeTimeline(e.copy(segments=listOf(e.segments[0],Segment(e.segments[1].text,5000))),2)
        assertFalse(complete.estimated)
        assertEquals(8000L,complete.totalMs)
    }
    @Test fun filtersDistinguishCompletedPartialAndGeneratingEpisodes() {
        val finished=episode().copy(segmentIndex=2,positionMs=3000)
        assertTrue(LibraryFilter.DOWNLOADED.matches(finished,3))
        assertFalse(LibraryFilter.UNFINISHED.matches(finished,3))
        assertTrue(LibraryFilter.UNFINISHED.matches(episode().copy(positionMs=500),2))
        assertFalse(LibraryFilter.DOWNLOADED.matches(episode(),2))
        assertTrue(LibraryFilter.GENERATING.matches(episode().copy(status="generating"),1))
        assertFalse(LibraryFilter.GENERATING.matches(episode().copy(status="paused"),1))
        val short=episode().copy(segments=listOf(Segment("嗯",400)))
        assertTrue(LibraryFilter.UNFINISHED.matches(short,1))
        assertFalse(LibraryFilter.UNFINISHED.matches(short.copy(positionMs=400),1))
    }
    @Test fun waitingForTheNextDownloadRemainsPausableButFinishedAudioCanReplay() {
        val e=episode().copy(status="generating")
        val waiting=PlaybackUi(e.id,1,2000,true,Player.STATE_ENDED)
        assertTrue(waiting.requested(e,2))
        assertFalse(waiting.copy(playWhenReady=false).requested(e,2))
        assertFalse(waiting.requested(e.copy(status="ready"),3))
        assertFalse(waiting.copy(episodeId="another").requested(e,2))
    }
}
