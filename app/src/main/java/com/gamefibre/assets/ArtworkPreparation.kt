package com.gamefibre.assets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class PreparedArtwork<T, R>(val source: T, val image: R?)

/** Publish each completed local handle without waiting for slower or failed neighbours. */
internal fun <T, R : Any> prepareArtwork(
    sources: List<T>,
    prepare: suspend (T) -> R?
) = channelFlow {
    val permits = Semaphore(4)
    sources.forEach { source ->
        launch {
            permits.withPermit {
                val image = try { prepare(source) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
                send(PreparedArtwork(source, image))
            }
        }
    }
}
