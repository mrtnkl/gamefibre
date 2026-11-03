package com.gamefibre.steam

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Keep payloads and cached manifests, but require JavaSteam to validate their chunks. */
internal fun requireDownloadValidation(stage: File) {
    val receipt = File(stage, ".DepotDownloader/depot.config")
    if (!receipt.isFile) return
    // JavaSteam 1.8.0 records earlier depots before the shared write queue drains.
    // After interruption those records can incorrectly bless preallocated files.
    // Without installed IDs it checks existing chunks and fetches only bad ones.
    val data = JSONObject(receipt.readText()).put("installedManifestIDs", JSONObject())
    val pending = File(receipt.parentFile, "depot.config.pending")
    try {
        FileOutputStream(pending).use { it.write(data.toString().toByteArray()); it.fd.sync() }
        Files.move(pending.toPath(), receipt.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { pending.delete() }
}
