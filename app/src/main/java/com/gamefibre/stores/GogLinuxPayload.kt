package com.gamefibre.stores

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Read the ZIP/ZIP64 payload embedded in GOG's Linux installer without executing its shell script. */
internal class GogLinuxPayload(override val fingerprint:String,private val url:String,private val size:Long):StorePayload {
    private data class Entry(val path:String,val size:Long,val compressed:Long,val crc:Long,val offset:Long,val method:Int,val link:Boolean)
    override suspend fun install(directory:File,io:DownloadIo,progress:(Long,Long)->Unit):File {
        directory.mkdirs()
        val archive=File(directory,"linux-installer.bin")
        io.installer(url,archive,size) { done,total -> progress(done,total) }
        val root=File(directory,"payload").apply { mkdirs() }
        RandomAccessFile(archive,"r").use { input ->
            val (prefix,entries)=try { index(input) } catch(e:Exception) { invalidate(archive); throw e }
            val selected=entries.filter { it.path.startsWith("data/noarch/") && !it.path.endsWith('/') }
            check(selected.isNotEmpty()) { "This GOG Linux installer does not contain a supported game archive." }
            val total=selected.fold(0L) { sum,e->Math.addExact(sum,e.size) }
            val existing=selected.filterNot { it.link }.sumOf { entry -> gamePath(root,entry.path.removePrefix("data/noarch/")).let { if(it.isFile)it.length()else 0L } }
            check(directory.usableSpace>=Math.addExact((total-existing).coerceAtLeast(0),total)) { "Not enough space to extract and export the Linux game files." }
            var done=0L; val links=mutableListOf<Pair<File,String>>()
            for(e in selected) {
                currentCoroutineContext().ensureActive()
                val target=gamePath(root,e.path.removePrefix("data/noarch/"))
                if(!e.link && target.isFile && target.length()==e.size && crc(target)==e.crc) { done+=e.size; progress(done,total); continue }
                input.seek(prefix+e.offset)
                val local=ByteArray(30).also { input.readFully(it) }; val h=ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
                check(h.int==0x04034b50) { "Invalid GOG installer file header. Cancel and retry the download." }
                val name=h.getShort(26).toInt() and 65535; val extra=h.getShort(28).toInt() and 65535
                input.seek(prefix+e.offset+30+name+extra)
                val stream=object:InputStream() {
                    var remaining=e.compressed
                    override fun read():Int { if(remaining==0L)return -1; val n=input.read(); if(n>=0)remaining--; return n }
                    override fun read(b:ByteArray,off:Int,len:Int):Int { if(remaining==0L)return -1; val n=input.read(b,off,minOf(len.toLong(),remaining).toInt()); if(n>0)remaining-=n; return n }
                }
                check(e.method in listOf(0,8)) { "The GOG installer uses an unsupported ZIP compression method." }
                val inflater=if(e.method==8)Inflater(true)else null
                val data=if(inflater!=null)InflaterInputStream(stream,inflater,65536)else stream
                val temp=File(directory,"work/${digest(e.path.toByteArray())}.part").apply { parentFile?.mkdirs() }
                try {
                    val checksum=CRC32(); var count=0L
                    temp.outputStream().use { output -> data.use { source -> val buffer=ByteArray(65536); while(true) {
                        currentCoroutineContext().ensureActive(); val n=source.read(buffer); if(n<0)break
                        count+=n; check(count<=e.size) { "A Linux game file expanded beyond its declared size." }
                        output.write(buffer,0,n); checksum.update(buffer,0,n); done+=n; progress(done,total)
                    } } }
                    if(count!=e.size || checksum.value!=e.crc) {
                        invalidate(archive)
                        error("A Linux game file failed verification. Resume to download the installer again.")
                    }
                    target.parentFile?.mkdirs()
                    if(e.link) { check(count<=4096); links.add(target to temp.readText()) }
                    else Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
                } catch(e:java.util.zip.ZipException) { invalidate(archive); error("The Linux installer is corrupt. Resume to download it again.") }
                finally { inflater?.end(); temp.delete() }
            }
            links.forEach { (target,text) ->
                val link=File(text); check(!link.isAbsolute)
                check(File(target.parentFile,text).canonicalPath.startsWith(root.canonicalPath+File.separator)) { "A Linux game link points outside the game folder." }
                Files.deleteIfExists(target.toPath()); Files.createSymbolicLink(target.toPath(),link.toPath())
            }
            links.forEach { check(it.first.exists()) { "The Linux archive contains a broken game link." } }
        }
        archive.delete(); File(archive.path+".complete").delete(); File(archive.path+".etag").delete()
        return root
    }
    private suspend fun crc(file:File):Long {
        val checksum=CRC32(); file.inputStream().use { input -> val b=ByteArray(65536); while(true) {
            currentCoroutineContext().ensureActive(); val n=input.read(b); if(n<0)break; checksum.update(b,0,n)
        } }; return checksum.value
    }
    private fun invalidate(archive:File) { archive.delete(); File(archive.path+".complete").delete(); File(archive.path+".etag").delete() }
    private fun index(file:RandomAccessFile):Pair<Long,List<Entry>> {
        val first=ByteArray(minOf(file.length(),2L*1024*1024).toInt()).also { file.seek(0);file.readFully(it) }
        fun signature(bytes:ByteArray,start:Int,magic:Int):Boolean = start>=0 && start+4<=bytes.size && ByteBuffer.wrap(bytes,start,4).order(ByteOrder.LITTLE_ENDIAN).int==magic
        val prefix=(0..(first.size-4)).firstOrNull { signature(first,it,0x04034b50) }?.toLong()?:error("The GOG installer contains no ZIP game data.")
        val tail=ByteArray(minOf(file.length(),65557L).toInt()).also { file.seek(file.length()-it.size);file.readFully(it) }
        val end=(tail.size-22 downTo 0).firstOrNull { signature(tail,it,0x06054b50) }?:error("The GOG installer ZIP directory is missing. Resume to retry.")
        val e=ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var count=e.getShort(end+10).toInt() and 65535
        var central=e.getInt(end+16).toLong() and 0xffffffffL
        if(count==65535 || central==0xffffffffL) {
            val locator=end-20; check(signature(tail,locator,0x07064b50)) { "Invalid GOG ZIP64 locator." }
            val offset=e.getLong(locator+8); file.seek(prefix+offset)
            val z=ByteBuffer.wrap(ByteArray(56).also { file.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
            check(z.int==0x06064b50); val entries=z.getLong(32); require(entries in 0..2_000_000); count=entries.toInt(); central=z.getLong(48)
        }
        require(central>=0 && central<file.length()); file.seek(prefix+central)
        val entries=ArrayList<Entry>(count)
        repeat(count) {
            val h=ByteBuffer.wrap(ByteArray(46).also { file.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
            check(h.int==0x02014b50) { "Invalid GOG ZIP directory." }
            val flags=h.getShort(8).toInt() and 65535; check(flags and 1==0) { "The Linux installer archive is encrypted." }
            val method=h.getShort(10).toInt() and 65535; val crc=h.getInt(16).toLong() and 0xffffffffL
            var compressed=h.getInt(20).toLong() and 0xffffffffL; var size=h.getInt(24).toLong() and 0xffffffffL
            val nameSize=h.getShort(28).toInt() and 65535; val extraSize=h.getShort(30).toInt() and 65535; val comment=h.getShort(32).toInt() and 65535
            val mode=h.getInt(38) ushr 16; var offset=h.getInt(42).toLong() and 0xffffffffL
            val name=String(ByteArray(nameSize).also { file.readFully(it) },if(flags and 2048!=0)Charsets.UTF_8 else runCatching { charset("Cp437") }.getOrDefault(Charsets.ISO_8859_1))
            val extra=ByteBuffer.wrap(ByteArray(extraSize).also { file.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
            while(extra.remaining()>=4) {
                val type=extra.short.toInt() and 65535; val length=extra.short.toInt() and 65535; check(length<=extra.remaining()); val stop=extra.position()+length
                if(type==1) { if(size==0xffffffffL)size=extra.long; if(compressed==0xffffffffL)compressed=extra.long; if(offset==0xffffffffL)offset=extra.long }
                check(extra.position()<=stop)
                extra.position(stop)
            }
            file.seek(file.filePointer+comment); require(size>=0 && compressed>=0 && offset>=0 && prefix+offset<file.length())
            entries.add(Entry(name,size,compressed,crc,offset,method,mode and 0xf000==0xa000))
        }
        return prefix to entries
    }
}
