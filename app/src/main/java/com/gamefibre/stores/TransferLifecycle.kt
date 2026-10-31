package com.gamefibre.stores

/** Worker updates must never undo a pause, cancellation, or completed export. */
internal object TransferLifecycle {
    private val working = setOf("preparing", "downloading", "exporting")

    fun acceptsWorkerUpdate(phase: String) = phase in working

    fun stoppedPhase(phase: String): String = when (phase) {
        "preparing", "downloading", "exporting", "pausing" -> "paused"
        else -> phase
    }
}
