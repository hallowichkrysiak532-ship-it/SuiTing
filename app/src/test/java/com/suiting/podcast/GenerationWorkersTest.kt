package com.suiting.podcast

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GenerationWorkersTest {
    private class Engine : SpeechEngine {
        var closed = 0
        override suspend fun synthesize(text: String, voice: String, file: File) = Unit
        override fun close() { closed++ }
    }

    @Test fun edgeWorkersOverlapButNeverExceedTheLimit() = runBlocking {
        withTimeout(5000) {
            val engines = mutableListOf<Engine>()
            val secondStarted = CompletableDeferred<Unit>()
            val indices = mutableListOf<Int>()
            var active = 0
            var maximum = 0
            generateSegments(8, 2, { Engine().also { engines += it } }) { _, index ->
                active++; maximum = maxOf(active, maximum)
                if (index == 0) secondStarted.await()
                if (index == 1) secondStarted.complete(Unit)
                yield()
                indices += index
                active--
            }
            assertEquals(2, maximum)
            assertEquals((0 until 8).toList(), indices.sorted())
            assertEquals(2, engines.size)
            assertTrue(engines.all { it.closed == 1 })
        }
    }

    @Test fun systemVoiceUsesOneEngineInParagraphOrder() = runBlocking {
        val engine = Engine()
        val indices = mutableListOf<Int>()
        generateSegments(4, 1, { engine }) { _, index -> indices += index; yield() }
        assertEquals(listOf(0, 1, 2, 3), indices)
        assertEquals(1, engine.closed)
    }

    @Test fun stopCancelsAllInFlightWorkAndClosesEveryEngine() = runBlocking {
        withTimeout(5000) {
            val engines = mutableListOf<Engine>()
            val bothStarted = CompletableDeferred<Unit>()
            var active = 0
            val job = launch {
                generateSegments(8, 2, { Engine().also { engines += it } }) { _, _ ->
                    if (++active == 2) bothStarted.complete(Unit)
                    awaitCancellation()
                }
            }
            bothStarted.await()
            job.cancelAndJoin()
            assertEquals(2, engines.size)
            assertTrue(engines.all { it.closed == 1 })
        }
    }

    @Test fun failedWorkerCancelsItsSiblingAndClosesEnginesBeforeReturning() = runBlocking {
        val engines = mutableListOf<Engine>()
        val siblingStarted = CompletableDeferred<Unit>()
        val failure = runCatching {
            withTimeout(5000) {
                generateSegments(8, 2, { Engine().also { engines += it } }) { _, index ->
                    if (index == 0) { siblingStarted.await(); error("network failed") }
                    siblingStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        }.exceptionOrNull()
        assertEquals("network failed", failure?.message)
        assertEquals(2, engines.size)
        assertTrue(engines.all { it.closed == 1 })
    }
}
