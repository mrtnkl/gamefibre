package com.gamefibre

import java.io.File
import java.nio.file.Files

/** Bound disposable data in private storage. Never inspect or prune game staging. */
internal object DiskCacheBudget {
    const val ARTWORK_BYTES = 128L * 1024 * 1024
    const val COVER_BYTES = 64L * 1024 * 1024
    const val DETAILS_BYTES = 32L * 1024 * 1024

    /** Keep recently browsed assets ahead of unused assets when enforcing the budget. */
    fun touch(file: File) {
        val now = System.currentTimeMillis()
        if (now - file.lastModified() >= 60_000) file.setLastModified(now)
    }

    @Synchronized fun trim(directory: File, maxBytes: Long, maxEntries: Int = 10_000, keep: File? = null) {
        require(maxBytes >= 0 && maxEntries >= 0)
        val files = directory.listFiles()?.filter { file ->
            file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                !file.name.endsWith(".new") && !file.name.endsWith(".bak") &&
                !File(file.path + ".new").exists() && !File(file.path + ".bak").exists()
        }.orEmpty()
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })) {
            if (bytes <= maxBytes && count <= maxEntries) break
            if (file == keep) continue
            val size = file.length()
            if (file.delete()) { bytes -= size; count-- }
        }
    }
}
