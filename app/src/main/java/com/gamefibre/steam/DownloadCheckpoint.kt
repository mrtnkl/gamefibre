package com.gamefibre.steam

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.Properties

internal enum class DownloadPhase {
    PREPARING, DOWNLOADING, PAUSING, PAUSED, EXPORTING, COMPLETE, FAILED, CANCELLED;
    val interruptedOnRestart get() = this in setOf(PREPARING, DOWNLOADING, PAUSING, EXPORTING)
    val resumable get() = this == PAUSED || this == FAILED
}
internal data class DownloadRequest(
    val appId: Int, val name: String, val format: String, val os: String,
    val language: String, val ownerId: String,
) {
    init {
        require(appId > 0 && name.isNotBlank()) { "Invalid saved game" }
        require(format in setOf("zip", "folder")) { "Invalid export format" }
        require(os in setOf("windows", "linux", "macos")) { "Invalid PC platform" }
        require(language in setOf("english", "dutch", "german", "french", "spanish", "italian", "portuguese", "brazilian", "polish", "russian", "japanese", "koreana", "schinese", "tchinese")) { "Invalid language" }
        require(ownerId.matches(Regex("[0-9]+"))) { "Missing Steam account" }
    }
    fun requireOwner(currentOwner: String) { check(ownerId == currentOwner) { "Sign in with the Steam account that started this download" } }
    fun stagingDirectory(root: File) = File(root, "downloads/$appId/$os-$language")
}
internal data class DownloadCheckpoint(
    val request: DownloadRequest, val phase: DownloadPhase, val message: String,
    val readyToExport: Boolean = false, val depotPercent: Float? = null,
    val bytes: Long? = null, val output: String? = null,
    val exportBytes: Long? = null, val exportTotalBytes: Long? = null,
) {
    /** Status-only events carry no new percentage; keep the last measurement within a phase. */
    fun withProgress(nextPhase: DownloadPhase, message: String, percent: Float? = null, receivedBytes: Long? = null) = copy(
        phase = nextPhase, message = message.take(400),
        depotPercent = percent ?: depotPercent.takeIf { phase == nextPhase },
        bytes = receivedBytes ?: bytes,
    )

    fun recover(): DownloadCheckpoint = if (phase.interruptedOnRestart) copy(
        phase = DownloadPhase.PAUSED,
        message = "Download interrupted. Resume to continue using the files already on this device.",
    ) else this
}
/** One durable checkpoint, atomically replaced; it never contains credentials. */
internal class DownloadCheckpointStore(private val file: File) {
    @Synchronized fun clear() { Files.deleteIfExists(file.toPath()) }
    @Synchronized fun save(value: DownloadCheckpoint) {
        file.parentFile?.mkdirs()
        val p = Properties().apply {
            setProperty("version", "1")
            setProperty("appId", value.request.appId.toString())
            setProperty("name", value.request.name)
            setProperty("format", value.request.format)
            setProperty("os", value.request.os)
            setProperty("language", value.request.language)
            setProperty("ownerId", value.request.ownerId)
            setProperty("phase", value.phase.name)
            setProperty("message", value.message)
            setProperty("readyToExport", value.readyToExport.toString())
            value.depotPercent?.let { setProperty("depotPercent", it.toString()) }
            value.bytes?.let { setProperty("bytes", it.toString()) }
            value.output?.let { setProperty("output", it) }
            value.exportBytes?.let { setProperty("exportBytes", it.toString()) }
            value.exportTotalBytes?.let { setProperty("exportTotalBytes", it.toString()) }
        }
        val temp = File(file.path + ".tmp")
        try {
            FileOutputStream(temp).use { p.store(it, null); it.fd.sync() }
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }
    @Synchronized fun load(): DownloadCheckpoint? {
        if (!file.exists()) return null
        return runCatching {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            require(p.getProperty("version") == "1")
            fun required(key: String) = requireNotNull(p.getProperty(key))
            DownloadCheckpoint(
                DownloadRequest(required("appId").toInt(), required("name"), required("format"), required("os"), required("language"), required("ownerId")),
                DownloadPhase.valueOf(required("phase")), required("message"),
                p.getProperty("readyToExport").toBoolean(), p.getProperty("depotPercent")?.toFloatOrNull(),
                p.getProperty("bytes")?.toLongOrNull(), p.getProperty("output"),
                p.getProperty("exportBytes")?.toLongOrNull(), p.getProperty("exportTotalBytes")?.toLongOrNull(),
            )
        }.getOrNull()
    }
}
