package com.gamefibre

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpTest {
    private val hosts = setOf("api.example.com")

    @Test fun validateAcceptsOnlyPlainHttpsOnAllowListedHosts() {
        assertEquals("api.example.com", Http.validate("https://api.example.com/v1?x=1", hosts).host)
        listOf(
            "http://api.example.com/v1",
            "https://api.example.com:8443/v1",
            "https://user@api.example.com/v1",
            "https://evil.example.com/v1",
            "https://api.example.com.evil.net/v1",
        ).forEach { address ->
            assertThrows(address, IllegalArgumentException::class.java) { Http.validate(address, hosts) }
        }
    }

    @Test fun successBodiesFailWhenTheyExceedTheLimit() {
        val error = assertThrows(IllegalStateException::class.java) {
            Http.read(ByteArrayInputStream(ByteArray(20_000)), 16_384, "Too big")
        }
        assertEquals("Too big", error.message)
        assertEquals(10, Http.read(ByteArrayInputStream(ByteArray(10)), 16_384, "Too big").size)
    }

    @Test fun errorBodiesAreTruncatedInsteadOfFailing() {
        assertEquals(1_000, Http.read(ByteArrayInputStream(ByteArray(20_000)), 1_000, null).size)
    }

    @Test fun headersAreCaseInsensitive() {
        val response = HttpResponse(429, mapOf("retry-after" to listOf("30")), ByteArray(0))
        assertEquals("30", response.header("Retry-After"))
        assertFalse(response.ok)
    }
}
