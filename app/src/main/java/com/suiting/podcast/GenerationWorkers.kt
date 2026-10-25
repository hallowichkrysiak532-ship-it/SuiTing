package com.suiting.podcast

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Independent engines avoid sharing a system TTS listener or an Edge connection between workers. */
internal suspend fun generateSegments(
    count: Int,
    workers: Int,
    engine: () -> SpeechEngine,
    generate: suspend (SpeechEngine, Int) -> Unit
) = coroutineScope {
    require(count >= 0 && workers > 0)
    val next = AtomicInteger()
    repeat(minOf(count, workers)) {
        launch {
            val speech = engine()
            try {
                while (true) {
                    ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= count) break
                    generate(speech, index)
                }
            } finally { speech.close() }
        }
    }
}
