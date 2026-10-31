package com.gamefibre

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext

internal data class ArtworkTarget(val title: String, val keys: List<String>)
internal data class ArtworkResult(val title: String, val message: String, val appliedKeys: Set<String> = emptySet())
internal data class BatchArtworkStatus(
    val total: Int,
    val current: String? = null,
    val results: List<ArtworkResult> = emptyList(),
    val running: Boolean = true,
    val stopping: Boolean = false,
    val message: String = "Preparing artwork…"
)

/** One queue, including search, cover lookup, download and local commit. Skips ambiguous titles. */
internal class BatchArtwork(
    private val search: suspend (String) -> List<GridGame>,
    private val grids: suspend (Int) -> List<GridArtwork>,
    private val save: suspend (ArtworkTarget, GridArtwork) -> Unit,
    private val pace: suspend () -> Unit = { delay(400) }
) {
    private val mutableStatus = MutableStateFlow<BatchArtworkStatus?>(null)
    val status = mutableStatus.asStateFlow()

    fun stop() { mutableStatus.value = mutableStatus.value?.copy(stopping = true) }

    fun start(scope: CoroutineScope, targets: List<ArtworkTarget>) {
        if (mutableStatus.value != null || targets.isEmpty()) return
        mutableStatus.value = BatchArtworkStatus(targets.size)
        scope.launch {
            var halt: String? = null
            try {
                for (target in targets) {
                    if (mutableStatus.value!!.stopping) break
                    mutableStatus.value = mutableStatus.value!!.copy(current = target.title)
                    val outcome = try {
                        val games = search(target.title).toMutableList()
                        if (GridTitleMatcher.fullMatches(target.title, games).isEmpty()) {
                            GridTitleMatcher.alternateQuery(target.title)?.let { alternate ->
                                pace()
                                games += search(alternate)
                            }
                        }
                        val matches = GridTitleMatcher.matches(target.title, games)
                        when (matches.size) {
                            0 -> "Skipped · no matching title"
                            1 -> {
                                pace()
                                val cover = grids(matches.single().id).firstOrNull()
                                if (cover == null) "Skipped · no suitable portrait cover"
                                else {
                                    coroutineContext.ensureActive()
                                    save(target, cover)
                                    "Applied cover #${cover.id}${if (cover.author.isBlank()) "" else " by ${cover.author}"}"
                                }
                            }
                            else -> "Skipped · multiple matching titles"
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: GridRequestRejected) {
                        halt = error.message
                        "Failed · ${error.message}"
                    } catch (error: Exception) { "Failed · ${error.message ?: "Could not apply artwork"}" }
                    val state = mutableStatus.value!!
                    mutableStatus.value = state.copy(results = state.results + ArtworkResult(target.title, outcome, if (outcome.startsWith("Applied")) target.keys.toSet() else emptySet()))
                    if (halt != null || state.stopping) break
                    pace()
                }
            } finally {
                val state = mutableStatus.value!!
                mutableStatus.value = state.copy(running = false, current = null,
                    message = halt ?: if (state.results.size < state.total) "Stopped. Saved covers are kept." else "Artwork complete")
            }
        }
    }
}
