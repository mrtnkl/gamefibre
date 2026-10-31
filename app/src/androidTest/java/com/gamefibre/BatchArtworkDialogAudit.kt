package com.gamefibre

import android.app.Activity
import android.app.Instrumentation
import android.widget.ScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel

/** Exercises real dialog measurement with delayed results, without SGDB requests or cover writes. */
internal object BatchArtworkDialogAudit {
    fun run(instrumentation: Instrumentation, activity: Activity) {
        fun <T> onUi(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val next = Channel<Unit>(Channel.UNLIMITED)
        val targets = (1..16).map { ArtworkTarget("LEGO Batman: A long title for scrolling results $it", listOf("test:$it")) }
        val batch = BatchArtwork(search = { next.receive(); listOf(GridGame(1, it)) },
            grids = { emptyList() }, save = { _, _ -> error("Must not save covers") }, pace = {})
        val dialog = onUi {
            batch.start(scope, targets)
            showBatchArtworkDialog(activity, NativeUi(activity), scope, batch) {}
        }
        try {
            instrumentation.waitForIdleSync()
            val initialHeight = onUi { dialog.window!!.decorView.height }
            check(initialHeight > 0)
            repeat(targets.size) { index ->
                onUi { check(next.trySend(Unit).isSuccess) }
                instrumentation.waitForIdleSync()
                onUi {
                    check(batch.status.value!!.results.size == index + 1)
                    check(dialog.window!!.decorView.height == initialHeight) { "Dialog height changed as results arrived" }
                }
            }
            onUi {
                check(batch.status.value!!.running == false)
                val scroll = dialog.content.parent as ScrollView
                check(scroll.canScrollVertically(1)) { "Results should scroll within the dialog" }
                scroll.scrollTo(0, dialog.content.height)
                check(scroll.scrollY > 0) { "Results did not scroll" }
                check(dialog.isShowing) { "Completion should remain visible until dismissed" }
            }
        } finally {
            onUi { dialog.dismiss(); scope.cancel(); next.close() }
        }
    }
}
