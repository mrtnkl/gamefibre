package com.gamefibre

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keep a file operation alive when the picker/activity rotates, without retaining the activity. */
internal class CollectionTransferModel : ViewModel() {
    data class Status(val running: Boolean, val message: String, val imported: Boolean = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow<Status?>(null)
    val status = mutableStatus.asStateFlow()
    fun run(message: String, importing: Boolean = false, operation: () -> String) {
        if (mutableStatus.value?.running == true) return
        mutableStatus.value = Status(true, message)
        scope.launch {
            try { mutableStatus.value = Status(false, operation(), importing) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableStatus.value = Status(false, "Collection transfer failed: ${error.message ?: "Could not read or write the selected file."}")
            }
        }
    }
    fun acknowledge() { if (mutableStatus.value?.running == false) mutableStatus.value = null }
    override fun onCleared() { scope.cancel() }
}
