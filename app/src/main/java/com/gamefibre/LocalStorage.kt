package com.gamefibre

import android.content.Context
import java.io.File

/** Only disposable acceleration data is cleared. Published artwork is library data. */
internal class LocalStorage(private val context:Context) {
    fun cacheBytes()=PrivateFiles.size(File(context.filesDir,"game-page-details-v1"))
    fun offlineArtworkBytes()=PrivateFiles.size(File(context.filesDir,"library-assets"))
    fun stagingBytes()=listOf("downloads","store-downloads").sumOf { PrivateFiles.size(File(context.filesDir,it)) }
    fun trimCaches(){DiskCacheBudget.trim(File(context.filesDir,"game-page-details-v1"),DiskCacheBudget.DETAILS_BYTES)}
    fun clearCaches(){PrivateFiles.deleteTree(File(context.filesDir,"game-page-details-v1"))}
}
