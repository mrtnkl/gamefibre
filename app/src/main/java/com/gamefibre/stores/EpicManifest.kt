package com.gamefibre.stores

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Native reader for Epic BuildPatch manifests/chunks, including encrypted chunk formats. */
internal object EpicManifest {
    private class Reader(bytes: ByteArray) {
        val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position: Int get()=buffer.position(); set(value) { require(value in 0..buffer.limit()); buffer.position(value) }
        fun int()=buffer.int
        fun uint()=int().toLong() and 0xffffffffL
        fun long()=buffer.long
        fun byte()=buffer.get().toInt() and 255
        fun take(n:Int):ByteArray { require(n>=0 && n<=buffer.remaining()) { "Truncated Epic manifest." }; return ByteArray(n).also { buffer.get(it) } }
        fun count():Int = int().also { require(it in 0..2_000_000 && it <= buffer.remaining()) { "Invalid Epic manifest entry count." } }
        fun string():String {
            val n=int(); require(n in -1_000_000..1_000_000)
            return when { n==0 -> ""; n>0 -> String(take(n).dropLast(1).toByteArray(),Charsets.UTF_8); else -> String(take(-n*2).dropLast(2).toByteArray(),Charsets.UTF_16LE) }
        }
        fun guid()=List(4) { uint() }.joinToString("") { "%08X".format(it) }
        fun section():Int { val start=position; val size=int(); require(size>=5 && size<=buffer.limit()-start); return start+size }
    }
    private data class Chunk(val guid:String, var rolling:Long=0, var sha:String="", var group:Int=0, var size:Int=0,
        var secret:String="00000000000000000000000000000000")
    private data class Part(val guid:String,val offset:Int,val size:Int)
    private data class Entry(var name:String="",var link:String="",var hash:String="",val parts:MutableList<Part> = mutableListOf())
    private fun hex(bytes:ByteArray)=bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(text:String):ByteArray { require(text.length%2==0); return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
    private fun guidBytes(guid:String)=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { guid.chunked(8).forEach { putInt(it.toLong(16).toInt()) } }.array()
    private fun encoded(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun aes(data:ByteArray,key:String,iv:ByteArray,tag:ByteArray):ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE,SecretKeySpec(unhex(key),"AES"),GCMParameterSpec(128,iv)); doFinal(data+tag)
    }

    fun parse(bytes:ByteArray, bases:List<String>, secrets:Map<String,String>): StorePayload {
        if (bytes.take(32).toByteArray().toString(Charsets.UTF_8).trimStart().startsWith("{")) return json(bytes,bases,secrets)
        val header=Reader(bytes)
        check(header.int()==0x44bec00c) { "Unknown Epic manifest format." }
        val headerSize=header.int(); val expanded=header.int(); val packed=header.int()
        require(expanded in 0..128*1024*1024 && packed>=0)
        val sha=hex(header.take(20)); val flags=header.byte(); val version=header.int()
        val secret=if(version>=22) header.guid() else ""; val tag=if(version>=22)header.take(16)else ByteArray(0)
        require(headerSize in header.position..bytes.size) { "Invalid Epic manifest header size." }
        header.position=headerSize
        val payload=header.take(bytes.size-headerSize)
        val data=if(flags and 1 !=0) inflate(payload,expanded) else payload
        check(data.size==expanded && digest(data,"SHA-1")==sha) { "The Epic manifest failed verification." }
        val r=Reader(data)
        val metaEnd=r.section(); r.byte(); val feature=r.int(); check(r.byte()==0) { "This legacy Epic build does not use supported chunks." }
        r.position=metaEnd
        val chunkEnd=r.section(); r.byte(); val count=r.count()
        val chunks=List(count) { Chunk(r.guid()) }
        chunks.forEach { it.rolling=r.long() }; chunks.forEach { it.sha=hex(r.take(20)) }
        chunks.forEach { it.group=r.byte() }; chunks.forEach { it.size=r.int().also { n -> require(n in 0..64*1024*1024) } }
        repeat(count) { r.long() }
        if(feature>=22) { chunks.forEach { it.secret=r.guid() }; repeat(count) { r.int() }; repeat(count) { r.take(16) } }
        check(r.position<=chunkEnd); r.position=chunkEnd
        val filesEnd=r.section(); val filesVersion=r.byte(); val fileCount=r.count(); val files=List(fileCount) { Entry() }
        files.forEach { it.name=r.string() }; files.forEach { it.link=r.string() }; files.forEach { it.hash=hex(r.take(20)) }
        repeat(fileCount) { r.byte() }
        repeat(fileCount) { repeat(r.count()) { r.string() } }
        files.forEach { file -> repeat(r.count()) {
            val partStart=r.position; val partSize=r.int(); require(partSize>=28)
            file.parts.add(Part(r.guid(),r.int(),r.int())); r.position=partStart+partSize
        } }
        if(filesVersion>=1) { repeat(fileCount) { if(r.int()!=0)r.take(16) }; repeat(fileCount) { r.string() } }
        if(filesVersion>=2) repeat(fileCount) { r.take(32) }
        check(r.position<=filesEnd); r.position=filesEnd
        if(flags and 2 !=0) {
            check(feature>=24) { "Unsupported encrypted Epic manifest version." }
            val customEnd=r.section(); r.position=customEnd
            val encryptedEnd=r.section(); r.byte(); r.int()
            val encryptedHeaderEnd=r.section(); r.int(); val stored=r.byte(); val decodedSize=r.int(); r.int()
            require(decodedSize in 0..MAX_EXPANDED_STORE_BYTES) { "Invalid encrypted Epic manifest size." }
            val iv=r.take(r.int().also { require(it in 12..32) }); r.position=encryptedHeaderEnd
            val ciphertext=r.take(r.int()); check(r.position<=encryptedEnd)
            val key=secrets[secret] ?: error("Epic did not provide the manifest decryption key. Resume to refresh your license.")
            val decrypted=aes(ciphertext,key,iv,tag)
            val decoded = if(stored and 1 !=0)inflate(decrypted,decodedSize)else decrypted
            check(decoded.size == decodedSize) { "The encrypted Epic manifest has an unexpected size." }
            val plain=Reader(decoded)
            plain.string(); plain.string(); repeat(plain.count()){plain.string()}; repeat(5){plain.string()}
            files.forEach { it.name=plain.string(); it.link=plain.string() }
        }
        return build(bytes,feature,chunks,files,bases,secrets)
    }

    private fun build(manifest:ByteArray,feature:Int,chunks:List<Chunk>,files:List<Entry>,bases:List<String>,secrets:Map<String,String>):StorePayload {
        val folder=when { feature>=22->"ChunksV5"; feature>=15->"ChunksV4"; feature>=6->"ChunksV3"; feature>=3->"ChunksV2"; else->"Chunks" }
        val blobs=chunks.associate { c ->
            val path=if(feature>=22) {
                val sec=if(c.secret.all { it=='0' }) "plain" else encoded(guidBytes(c.secret))
                "$folder/$sec/${"%02d".format(Locale.ROOT,c.group)}/${encoded(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(c.rolling).array())}_${encoded(guidBytes(c.guid))}.chunk"
            } else "$folder/${"%02d".format(Locale.ROOT,c.group)}/${"%016X".format(c.rolling)}_${c.guid}.chunk"
            c.guid to StoreBlob("epic:${c.guid}:${c.sha}",c.size,{bases.map { it.trimEnd('/')+"/"+path }},c.sha,"SHA-1",decode={decodeChunk(it,c.guid,c.size,secrets)})
        }
        val output=files.map { file ->
            val parts=file.parts.map { part -> StorePart(requireNotNull(blobs[part.guid]) { "Epic manifest refers to a missing chunk." },part.offset,part.size) }
            val size=parts.fold(0L) { sum,p -> Math.addExact(sum,p.size.toLong()) }
            StoreFile(file.name,size,file.hash,"SHA-1",parts,file.link)
        }
        return ChunkPayload(digest(manifest),output)
    }

    private fun decodeChunk(bytes:ByteArray,guid:String,size:Int,secrets:Map<String,String>):ByteArray {
        val r=Reader(bytes); check(r.int()==0xb1fe3aa2.toInt()) { "Unknown Epic chunk format." }
        val version=r.int(); val headerSize=r.int(); val compressed=r.int()
        check(r.guid()==guid) { "Epic returned the wrong chunk." }; r.long(); val flags=r.byte()
        val sha=if(version>=2)r.take(20)else ByteArray(0); if(version>=2)r.byte()
        if(version>=3)check(r.int()==size) { "Epic chunk size changed." }
        val secret=if(version>=4)r.guid()else""; val tag=if(version>=4)r.take(16)else ByteArray(0)
        r.position=headerSize; var data=r.take(bytes.size-headerSize); check(data.size==compressed)
        if(flags and 2 !=0) data=aes(data,secrets[secret]?:error("Epic did not provide a chunk decryption key."),sha.copyOf(12),tag)
        if(flags and 1 !=0)data=inflate(data,size)
        check(data.size==size); return data
    }

    private fun json(bytes:ByteArray,bases:List<String>,secrets:Map<String,String>):StorePayload {
        val root=JSONObject(String(bytes,Charsets.UTF_8))
        fun blob(text:String):ByteArray { require(text.length%3==0); return text.chunked(3).map { it.toInt().also { n->require(n in 0..255) }.toByte() }.toByteArray() }
        fun number(text:String):Long { val b=blob(text); require(b.size<=8); return b.foldIndexed(0L) { i,n,v -> n or ((v.toLong() and 255) shl (i*8)) } }
        val feature=number(root.optString("ManifestFileVersion","013000000000")).toInt()
        val hashes=root.getJSONObject("ChunkHashList"); val shas=root.getJSONObject("ChunkShaList"); val groups=root.getJSONObject("DataGroupList")
        val chunks=hashes.keys().asSequence().map { key -> Chunk(key.uppercase(),number(hashes.getString(key)),shas.getString(key),number(groups.getString(key)).toInt(),1024*1024) }.toList()
        val array=root.getJSONArray("FileManifestList")
        val files=(0 until array.length()).map { i -> val f=array.getJSONObject(i); Entry(f.getString("Filename"),"",hex(blob(f.getString("FileHash")))).also { out ->
            val parts=f.getJSONArray("FileChunkParts"); repeat(parts.length()) { p -> val part=parts.getJSONObject(p); out.parts.add(Part(part.getString("Guid").uppercase(),number(part.getString("Offset")).toInt(),number(part.getString("Size")).toInt())) }
        } }
        return build(bytes,feature,chunks,files,bases,secrets)
    }
}
