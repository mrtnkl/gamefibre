package com.gamefibre.stores

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface StorePayload {
    val fingerprint: String
    suspend fun install(directory: File, io: DownloadIo, progress: (Long, Long) -> Unit): File
}
internal data class StoreBlob(
    val key: String, val size: Int, val urls: () -> List<String>,
    val hash: String = "", val algorithm: String = "SHA-1",
    val range: Pair<Long, Long>? = null, val decode: (ByteArray) -> ByteArray = { it },
)
internal data class StorePart(val blob: StoreBlob, val offset: Int, val size: Int)
internal data class StoreFile(val path: String, val size: Long, val hash: String, val algorithm: String, val parts: List<StorePart>, val link: String = "")

internal class ChunkPayload(override val fingerprint: String, private val files: List<StoreFile>) : StorePayload {
    override suspend fun install(directory: File, io: DownloadIo, progress: (Long, Long) -> Unit): File {
        check(files.isNotEmpty()) { "No game files are available for the selected platform and language." }
        val root = File(directory, "payload").apply { mkdirs() }
        val cache = File(directory, "chunks").apply { mkdirs() }
        val marks = File(directory,"file-checksums").apply { mkdirs() }
        val uses=files.flatMap { file -> file.parts.map { it.blob.key }.distinct() }.groupingBy { it }.eachCount().toMutableMap()
        fun release(file:StoreFile) { file.parts.map { it.blob.key }.distinct().forEach { key ->
            val left=uses.getValue(key)-1; uses[key]=left
            if(left==0) { val stored=File(cache,digest(key.toByteArray())+".chunk"); stored.delete(); File(stored.path+".sha256").delete() }
        } }
        val total = files.filter { it.link.isBlank() }.fold(0L) { sum, file -> Math.addExact(sum, file.size) }
        val existing = com.gamefibre.PrivateFiles.size(root)
        val needed=Math.addExact((total-existing).coerceAtLeast(0),Math.addExact(total,16L*1024*1024))
        check(directory.usableSpace >= needed) { "Not enough free space for the game files and their export." }
        var done = 0L
        for (file in files.filter { it.link.isBlank() }) {
            currentCoroutineContext().ensureActive()
            val target = gamePath(root, file.path)
            val marker=File(marks,digest(file.path.toByteArray()))
            val expected=if(file.hash.isNotBlank())file.hash else if(marker.isFile)marker.readText()else""
            val algorithm=if(file.hash.isNotBlank())file.algorithm else "SHA-256"
            if (target.isFile && target.length() == file.size && expected.isNotBlank() && fileDigest(target,algorithm).equals(expected,true)) {
                done += file.size; progress(done,total); release(file); continue
            }
            target.parentFile?.mkdirs()
            val temporary = File(directory,"work/${digest(file.path.toByteArray())}.part").apply { parentFile?.mkdirs() }
            try {
                RandomAccessFile(temporary,"rw").use { output ->
                    output.setLength(0)
                    for (part in file.parts) {
                        currentCoroutineContext().ensureActive()
                        val blob = part.blob
                        val cached = File(cache, digest(blob.key.toByteArray()) + ".chunk")
                        val checksum = File(cached.path + ".sha256")
                        val valid = cached.isFile && cached.length() == blob.size.toLong() &&
                            if (blob.hash.isNotBlank()) fileDigest(cached,blob.algorithm).equals(blob.hash,true)
                            else checksum.isFile && fileDigest(cached,"SHA-256") == checksum.readText()
                        if (!valid) {
                            val raw = io.bytes(blob.urls(), (blob.size.toLong() * 2 + 1024 * 1024).coerceAtMost(128 * 1024 * 1024).toInt(),blob.range)
                            val data = blob.decode(raw)
                            check(data.size == blob.size && (blob.hash.isBlank() || digest(data,blob.algorithm).equals(blob.hash,true))) { cached.delete(); "A downloaded game chunk failed verification. Resume to retry." }
                            val temp = File(cached.path + ".tmp")
                            temp.outputStream().use { it.write(data) }
                            Files.move(temp.toPath(),cached.toPath(),StandardCopyOption.REPLACE_EXISTING)
                            if (blob.hash.isBlank()) checksum.writeText(digest(data))
                        }
                        check(part.offset >= 0 && part.size >= 0 && part.offset.toLong()+part.size <= blob.size) { "A store manifest contains an invalid chunk range." }
                        RandomAccessFile(cached,"r").use { input ->
                            input.seek(part.offset.toLong()); var remaining=part.size; val buffer=ByteArray(65536)
                            while (remaining>0) { currentCoroutineContext().ensureActive(); val n=input.read(buffer,0,minOf(buffer.size,remaining)); check(n>0); output.write(buffer,0,n); remaining-=n }
                        }
                        done+=part.size; progress(done,total)
                    }
                    check(output.length()==file.size) { "A game file has an unexpected size." }; output.fd.sync()
                }
                if (file.hash.isNotBlank() && !fileDigest(temporary,file.algorithm).equals(file.hash,true)) {
                    file.parts.forEach { part -> File(cache,digest(part.blob.key.toByteArray())+".chunk").delete() }
                    error("A game file failed verification. Resume to download it again.")
                }
                Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
                if(file.hash.isBlank())marker.writeText(fileDigest(target,"SHA-256"))
                release(file)
            } finally { temporary.delete() }
        }
        // Preserve only links whose resolved targets remain inside the staged game folder.
        for (file in files.filter { it.link.isNotBlank() }) {
            val target = gamePath(root,file.path); target.parentFile?.mkdirs()
            val link = File(file.link.replace('\\','/'))
            check(!link.isAbsolute) { "A game contains an absolute symbolic link." }
            val destination = File(target.parentFile,link.path).canonicalFile
            check(destination.path.startsWith(root.canonicalPath+File.separator)) { "A game link points outside its folder." }
            Files.deleteIfExists(target.toPath()); Files.createSymbolicLink(target.toPath(),link.toPath())
        }
        for (file in files.filter { it.link.isNotBlank() }) check(gamePath(root,file.path).exists()) { "A game contains a broken symbolic link." }
        cache.deleteRecursively()
        return root
    }
}
