package com.gamefibre.steam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streams into pending MediaStore rows. No all-files or legacy storage permission. */
class DownloadsExporter(private val context: Context) {
    companion object {
        fun safeName(name: String): String = name.replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_")
            .trim().trim('.').take(100).ifBlank { "Steam game" }
        /** On process death, delete the incomplete export, including partially published folders. */
        fun recover(context: Context) {
            val journal = File(context.filesDir, "pending-export.txt")
            if (!journal.exists()) return
            val failed = mutableListOf<String>()
            val entries = journal.readLines().filter { it.isNotBlank() }
            entries.filterNot { it.startsWith("directory:") }.forEach { line ->
                try { context.contentResolver.delete(Uri.parse(line), null, null) } catch (_: Exception) { failed.add(line) }
            }
            // MediaStore removes files, but does not remove the directories it created.
            // Only try directories recorded for this export, deepest first. File.delete()
            // removes empty directories only; never recursively delete shared storage.
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
            entries.filter { it.startsWith("directory:") }.distinct().sortedByDescending { it.length }.forEach { line ->
                try {
                    val relative = Uri.decode(line.removePrefix("directory:"))
                    val directory = File(downloads, relative).canonicalFile
                    check(directory.path.startsWith(downloads.path + File.separator)) { "Invalid export directory" }
                    // Also cover a process dying after insert but before its URI was journaled.
                    @Suppress("DEPRECATION") // Includes pending rows on API 29 as well as newer Android versions.
                    val pending = MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                    val unrecorded = mutableListOf<Uri>()
                    context.contentResolver.query(pending, arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.IS_PENDING} = 1",
                        arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$relative/"), null)?.use { cursor ->
                        while (cursor.moveToNext()) unrecorded.add(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString()))
                    }
                    unrecorded.forEach { context.contentResolver.delete(it, null, null) }
                    if (directory.exists() && (!directory.isDirectory || !directory.delete())) failed.add(line)
                } catch (_: Exception) { failed.add(line) }
            }
            if (failed.isEmpty()) journal.delete() else journal.writeText(failed.joinToString("\n", postfix = "\n"))
        }
    }
    private val resolver = context.contentResolver
    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    private val journal = File(context.filesDir, "pending-export.txt")
    suspend fun export(source: File, gameName: String, format: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        recover(context)
        check(!journal.exists()) { "An earlier export could not be cleaned up. Check Downloads and retry." }
        val created = mutableListOf<Uri>()
        val directories = mutableSetOf<String>()
        try {
            val ancestors = mutableSetOf<String>()
            val files = source.walkTopDown().onEnter { directory ->
                if (directory != source && directory.name == ".DepotDownloader") false else {
                    val canonical = directory.canonicalPath
                    check(canonical == source.canonicalPath || canonical.startsWith(source.canonicalPath + File.separator)) { "A game directory points outside the download directory" }
                    check(ancestors.add(canonical)) { "A game directory contains a symbolic link cycle" }
                    true
                }
            }.onLeave { ancestors.remove(it.canonicalPath) }
                .onFail { _, error -> throw error }.filter { it.isFile }.toList()
            check(files.isNotEmpty()) { "The store returned no game files for the selected platform and language." }
            for (file in files) check(file.canonicalPath.startsWith(source.canonicalPath + File.separator)) { "A game file points outside the download directory" }
            val totalBytes = files.sumOf { it.length() }
            var copiedBytes = 0L
            var lastProgress = android.os.SystemClock.elapsedRealtime()
            onProgress(0, totalBytes)
            val copied: (Int) -> Unit = { count ->
                copiedBytes += count
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastProgress >= 100 || copiedBytes == totalBytes) {
                    onProgress(copiedBytes, totalBytes)
                    lastProgress = now
                }
            }
            if (format == "zip") {
                val uri = create(safeName(gameName) + ".zip", Environment.DIRECTORY_DOWNLOADS + "/", "application/zip", created)
                open(uri).buffered().use { raw -> ZipOutputStream(raw).use { zip ->
                    for (file in files) {
                        currentCoroutineContext().ensureActive()
                        val path = file.relativeTo(source).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(path))
                        file.inputStream().buffered().use { copy(it, zip, copied) }
                        zip.closeEntry()
                    }
                } }
                currentCoroutineContext().ensureActive()
                publish(created)
                journal.delete()
                val actual = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                return "Downloads/${actual ?: safeName(gameName) + ".zip"}"
            }
            val folderName = uniqueFolder(safeName(gameName))
            for (file in files) {
                currentCoroutineContext().ensureActive()
                val relative = file.relativeTo(source).invariantSeparatorsPath
                val parent = relative.substringBeforeLast('/', "")
                val path = Environment.DIRECTORY_DOWNLOADS + "/" + folderName + "/" + if (parent.isEmpty()) "" else "$parent/"
                // Persist every ancestor before inserting a row: Android may create the
                // directories even if the process dies before insert returns a URI.
                val parts = (folderName + if (parent.isEmpty()) "" else "/$parent").split('/')
                for (index in parts.indices) {
                    val directory = parts.take(index + 1).joinToString("/")
                    if (directories.add(directory)) record("directory:${Uri.encode(directory, "/")}")
                }
                val uri = create(file.name, path, "application/octet-stream", created, exact = true)
                open(uri).buffered().use { output -> file.inputStream().buffered().use { copy(it, output, copied) } }
            }
            currentCoroutineContext().ensureActive()
            publish(created)
            journal.delete()
            return "Downloads/$folderName/"
        } catch (e: Throwable) {
            created.forEach { runCatching { resolver.delete(it, null, null) } }
            recover(context)
            throw e
        }
    }
    private fun uniqueFolder(base: String): String {
        var candidate = base
        var suffix = 1
        while (true) {
            val prefix = "${Environment.DIRECTORY_DOWNLOADS}/$candidate/"
            val escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val exists = resolver.query(collection, arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? ESCAPE '\\'", arrayOf("$escaped%"), null)?.use { it.moveToFirst() } ?: false
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), candidate)
            if (!exists && !directory.exists()) return candidate
            candidate = "$base (${suffix++})"
        }
    }
    private fun create(name: String, path: String, mime: String, created: MutableList<Uri>, exact: Boolean = false): Uri {
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, path)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: error("Android could not create a file in Downloads")
        created.add(uri)
        record(uri.toString())
        if (exact) {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH), null, null, null)?.use {
                check(it.moveToFirst() && it.getString(0) == name && it.getString(1) == path) {
                    "Android cannot preserve a game filename in this folder. Choose ZIP archive to keep the original filenames."
                }
            } ?: error("Android could not confirm the exported filename")
        }
        return uri
    }
    private fun record(entry: String) {
        FileOutputStream(journal, true).use { out -> out.write((entry + "\n").toByteArray()); out.fd.sync() }
    }
    private fun open(uri: Uri): OutputStream = resolver.openOutputStream(uri, "w") ?: error("Cannot write to Android Downloads")
    private fun publish(uris: List<Uri>) {
        for (uri in uris) check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) { "Android could not publish a downloaded file" }
    }
    private suspend fun copy(input: InputStream, output: OutputStream, onCopied: (Int) -> Unit) {
        val bytes = ByteArray(256 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(bytes)
            if (count < 0) break
            output.write(bytes, 0, count)
            onCopied(count)
        }
    }
}
