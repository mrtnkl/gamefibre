package com.gamefibre

import java.io.DataInputStream
import java.io.InputStream
import org.tukaani.xz.XZInputStream

/** Sorted, prefix-compressed titles: retain bytes and block offsets, not a map of every title. */
internal class SteamTitleIndex private constructor(
    val size: Int,
    private val blockSize: Int,
    private val offsets: IntArray,
    private val data: ByteArray,
    private val relaxed: SteamTitleIndex? = null,
) {
    private val matches = object : LinkedHashMap<String, Int>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 2048
    }

    fun steamId(title: String): Int? = findNormalized(BeatIndex.normalize(title))
        ?: relaxed?.findNormalized(SteamTitleMatcher.relaxedKey(title))

    fun findNormalized(title: String): Int? {
        if (title.isEmpty() || size == 0) return null
        synchronized(matches) { matches[title]?.let { return it.takeIf { id -> id > 0 } } }
        val result = search(title)
        synchronized(matches) { matches[title] = result ?: 0 }
        return result
    }

    private fun search(title: String): Int? {
        // Find the last block whose first title is <= the requested title.
        var low = 0
        var high = offsets.lastIndex
        var block = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val first = cursor(middle).title(byteArrayOf()).toString(Charsets.UTF_8)
            if (first <= title) { block = middle; low = middle + 1 } else high = middle - 1
        }
        if (block < 0) return null
        val input = cursor(block)
        var previous = byteArrayOf()
        repeat(minOf(blockSize, size - block * blockSize)) {
            previous = input.title(previous)
            val appId = input.varint()
            require(appId > 0) { "Invalid Steam ID" }
            val comparison = previous.toString(Charsets.UTF_8).compareTo(title)
            if (comparison == 0) return appId
            if (comparison > 0) return null
        }
        return null
    }

    private fun cursor(block: Int) = Cursor(data, offsets[block], offsets.getOrElse(block + 1) { data.size })

    private class Cursor(val data: ByteArray, var position: Int, val end: Int) {
        fun varint(): Int {
            var result = 0
            for (shift in 0..28 step 7) {
                require(position < end) { "Truncated Steam title index" }
                val value = data[position++].toInt() and 255
                require(shift < 28 || value <= 7) { "Oversized Steam title integer" }
                result = result or ((value and 127) shl shift)
                if (value and 128 == 0) return result
            }
            error("Invalid Steam title integer")
        }

        fun title(previous: ByteArray): ByteArray {
            val prefix = varint()
            val suffix = varint()
            require(prefix <= previous.size && suffix <= 16384 && prefix + suffix in 1..16384 && suffix <= end - position) {
                "Invalid Steam title prefix or length"
            }
            return previous.copyOf(prefix + suffix).also {
                data.copyInto(it, prefix, position, position + suffix)
                position += suffix
            }
        }
    }

    companion object {
        fun read(stream: InputStream): SteamTitleIndex {
            // 16 MiB limits the temporary XZ decoder dictionary; only compact bytes survive loading.
            DataInputStream(XZInputStream(stream, 16 * 1024).buffered()).use { input ->
                require(input.readInt() == 0x53544958) { "Unsupported Steam title index" }
                val version = input.readInt().also { require(it in 1..2) }
                val exact = readTable(input)
                val relaxed = if (version == 2) readTable(input) else null
                require(input.read() == -1) { "Unexpected Steam title data" }
                return SteamTitleIndex(exact.size, exact.blockSize, exact.offsets, exact.data, relaxed)
            }
        }

        private fun readTable(input: DataInputStream): SteamTitleIndex {
            val count = input.readInt().also { require(it in 0..1_000_000) }
            val blockSize = input.readInt().also { require(it in 1..256) }
            val length = input.readInt().also { require(it in 0..32 * 1024 * 1024) }
            val offsets = IntArray((count + blockSize - 1) / blockSize) { input.readInt() }
            offsets.forEachIndexed { index, offset ->
                require(offset in 0 until length && (if (index == 0) offset == 0 else offset > offsets[index - 1]))
            }
            require(count != 0 || length == 0)
            val data = ByteArray(length).also(input::readFully)
            return SteamTitleIndex(count, blockSize, offsets, data)
        }
    }
}
