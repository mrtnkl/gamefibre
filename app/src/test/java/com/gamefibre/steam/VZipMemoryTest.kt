package com.gamefibre.steam

import `in`.dragonbra.javasteam.util.VZipUtil
import `in`.dragonbra.javasteam.util.stream.MemoryStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream
import java.util.concurrent.atomic.AtomicReference

class VZipMemoryTest {
    @Test fun dictionariesAreReusedAcrossWorkerThreadsAndChunksStillVerify() {
        val expected = "GameFibre chunk verification ".repeat(32).toByteArray()
        val options = LZMA2Options().apply { dictSize = 65536 }
        val compressed = ByteArrayOutputStream()
        val encoder = LZMAOutputStream(compressed, options, false)
        val properties = encoder.props
        encoder.use { it.write(expected) }
        val encoded = ByteArrayOutputStream()
        DataOutputStream(encoded).use {
            it.writeShort(java.lang.Short.reverseBytes(0x5a56.toShort()).toInt())
            it.writeByte('a'.code); it.writeInt(0); it.writeByte(properties)
            it.writeInt(Integer.reverseBytes(options.dictSize)); it.write(compressed.toByteArray())
            it.writeInt(Integer.reverseBytes(CRC32().apply { update(expected) }.value.toInt()))
            it.writeInt(Integer.reverseBytes(expected.size))
            it.writeShort(java.lang.Short.reverseBytes(0x767a.toShort()).toInt())
        }
        val data = encoded.toByteArray()
        val failure = AtomicReference<Throwable>()
        repeat(40) {
            Thread {
                try {
                    val output = ByteArray(expected.size)
                    assertEquals(expected.size, VZipUtil.decompress(MemoryStream(data), output, true))
                    assertArrayEquals(expected, output)
                } catch (error: Throwable) { failure.set(error) }
            }.apply { start(); join() }
        }
        failure.get()?.let { throw it }
        val field = VZipUtil::class.java.getDeclaredField("windowBuffers").apply { isAccessible = true }
        val buffers = field.get(null) as Collection<*>
        assertTrue(buffers.size <= 2)
        assertTrue(buffers.sumOf { (it as ByteArray).size } <= 16 * 1024 * 1024)
    }
}
