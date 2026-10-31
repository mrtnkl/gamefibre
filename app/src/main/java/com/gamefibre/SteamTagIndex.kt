package com.gamefibre

import android.content.Context
import java.io.DataInputStream
import java.io.InputStream
import org.tukaani.xz.XZInputStream

/** Offline community tags, independent of store feature categories in GameDetails. */
internal class SteamTagIndex private constructor(
    private val names: List<String>, private val count: Int, private val blockSize: Int,
    private val offsets: IntArray, private val data: ByteArray,
) {
    private val firstIds = IntArray(offsets.size) { cursor(it).integer() }
    private val cache = object : LinkedHashMap<Int, List<String>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<String>>?) = size > 512
    }

    init {
        var previousId = 0
        offsets.indices.forEach { block ->
            val input = cursor(block)
            var id = 0
            repeat(minOf(blockSize, count - block * blockSize)) {
                val delta = input.integer()
                require(delta > 0 && delta <= Int.MAX_VALUE - id) { "Invalid Steam tag AppID" }
                id += delta
                require(id > previousId) { "Unsorted Steam tag AppIDs" }
                previousId = id
                val tags = input.integer().also { require(it in 1..names.size) }
                repeat(tags) { require(input.integer() in names.indices) { "Invalid Steam tag ID" } }
            }
            require(input.position == input.end) { "Unexpected Steam tag block data" }
        }
    }

    fun tags(appId: Int): List<String> {
        if (appId <= 0) return emptyList()
        synchronized(cache) { cache[appId]?.let { return it } }
        var low = 0
        var high = firstIds.lastIndex
        var block = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (firstIds[middle] <= appId) { block = middle; low = middle + 1 } else high = middle - 1
        }
        val result = if (block < 0) emptyList() else find(block, appId)
        synchronized(cache) { cache[appId] = result }
        return result
    }

    private fun find(block: Int, appId: Int): List<String> {
        val input = cursor(block)
        var id = 0
        repeat(minOf(blockSize, count - block * blockSize)) {
            id += input.integer()
            val tags = input.integer()
            if (id == appId) return List(tags) { names[input.integer()] }
            if (id > appId) return emptyList()
            repeat(tags) { input.integer() }
        }
        return emptyList()
    }

    private fun cursor(block: Int) = Cursor(data, offsets[block], offsets.getOrElse(block + 1) { data.size })

    private class Cursor(val data: ByteArray, var position: Int, val end: Int) {
        fun integer(): Int {
            var result = 0
            for (shift in 0..28 step 7) {
                require(position < end) { "Truncated Steam tags" }
                val value = data[position++].toInt() and 255
                require(shift < 28 || value <= 7) { "Oversized Steam tag integer" }
                result = result or ((value and 127) shl shift)
                if (value and 128 == 0) return result
            }
            error("Invalid Steam tag integer")
        }
    }

    companion object {
        @Volatile private var loaded: SteamTagIndex? = null
        fun load(context: Context): SteamTagIndex = loaded ?: synchronized(this) {
            loaded ?: context.assets.open("steam-tags-v1.bin.xz").use(::read).also { loaded = it }
        }

        fun read(stream: InputStream): SteamTagIndex {
            DataInputStream(XZInputStream(stream, 16 * 1024).buffered()).use { input ->
                require(input.readInt() == 0x53545447 && input.readInt() == 1) { "Unsupported Steam tag index" }
                val nameCount = input.readInt().also { require(it in 1..4096) }
                val count = input.readInt().also { require(it in 0..1_000_000) }
                val blockSize = input.readInt().also { require(it in 1..256) }
                val length = input.readInt().also { require(it in 0..16 * 1024 * 1024) }
                fun integer(): Int {
                    var result = 0
                    for (shift in 0..28 step 7) {
                        val value = input.readUnsignedByte()
                        require(shift < 28 || value <= 7)
                        result = result or ((value and 127) shl shift)
                        if (value and 128 == 0) return result
                    }
                    error("Invalid Steam tag name length")
                }
                val names = List(nameCount) {
                    val size = integer().also { require(it in 1..4096) }
                    val bytes = ByteArray(size).also(input::readFully)
                    String(bytes, Charsets.UTF_8).also { require(it.isNotBlank()) }
                }
                require(names.distinct().size == names.size)
                val offsets = IntArray((count + blockSize - 1) / blockSize) { input.readInt() }
                offsets.forEachIndexed { index, offset ->
                    require(offset in 0 until length && if (index == 0) offset == 0 else offset > offsets[index - 1])
                }
                require(count != 0 || length == 0)
                val data = ByteArray(length).also(input::readFully)
                require(input.read() == -1) { "Unexpected Steam tag data" }
                return SteamTagIndex(names, count, blockSize, offsets, data)
            }
        }
    }
}
