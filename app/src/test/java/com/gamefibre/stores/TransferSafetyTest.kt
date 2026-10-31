package com.gamefibre.stores

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

class TransferSafetyTest {
    @Test fun lateWorkerUpdatesCannotUndoAPauseCancellationOrCompletion() {
        for (phase in listOf("pausing", "paused", "cancelled", "failed", "complete")) {
            assertFalse("Late worker update must not overwrite $phase", TransferLifecycle.acceptsWorkerUpdate(phase))
        }
        for (phase in listOf("preparing", "downloading", "exporting")) {
            assertTrue(TransferLifecycle.acceptsWorkerUpdate(phase))
        }
    }

    @Test fun stoppedWorkersAlwaysLeaveAResumableOrTerminalPhase() {
        for (phase in listOf("preparing", "downloading", "exporting", "pausing")) {
            assertEquals("paused", TransferLifecycle.stoppedPhase(phase))
        }
        for (phase in listOf("paused", "failed", "cancelled", "complete")) {
            assertEquals(phase, TransferLifecycle.stoppedPhase(phase))
        }
    }

    private fun compressed(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DeflaterOutputStream(output).use { it.write(bytes) }
    }.toByteArray()

    @Test fun decompressionRejectsOversizedAndNegativeDeclaredLimitsBeforeReading() {
        for (limit in listOf(-1, MAX_EXPANDED_STORE_BYTES + 1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { inflate(byteArrayOf(), limit) }
        }
    }

    @Test fun decompressionRejectsDataThatExpandsPastTheDeclaredLimit() {
        val packed = compressed(ByteArray(128 * 1024))
        assertThrows(IllegalStateException::class.java) { inflate(packed, 1024) }
    }

    @Test fun boundedDecompressionPreservesValidData() {
        val data = "verified manifest".toByteArray()
        assertArrayEquals(data, inflate(compressed(data), data.size))
    }

    @Test fun epicRejectsOversizedOuterManifestBeforeAllocating() {
        val header = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(0x44bec00c).putInt(41).putInt(Int.MAX_VALUE).putInt(0).array()
        assertThrows(IllegalArgumentException::class.java) { EpicManifest.parse(header, emptyList(), emptyMap()) }
    }

    @Test fun epicRejectsOversizedEncryptedSectionBeforeDecryptingOrExpanding() {
        fun int(value: Int) = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        fun section(data: ByteArray) = int(data.size + 4) + data
        val metadata = section(byteArrayOf(0) + int(24) + byteArrayOf(0))
        val emptyList = section(byteArrayOf(0) + int(0))
        val encryptedHeader = section(int(0) + byteArrayOf(1) + int(Int.MAX_VALUE) + int(0))
        val encrypted = section(byteArrayOf(0) + int(0) + encryptedHeader)
        val data = metadata + emptyList + emptyList + section(byteArrayOf(0)) + encrypted
        val sha = java.security.MessageDigest.getInstance("SHA-1").digest(data)
        val header = int(0x44bec00c) + int(73) + int(data.size) + int(data.size) + sha + byteArrayOf(2) + int(24) + ByteArray(32)
        val error = assertThrows(IllegalArgumentException::class.java) { EpicManifest.parse(header + data, listOf(), emptyMap()) }
        assertEquals("Invalid encrypted Epic manifest size.", error.message)
    }
}
