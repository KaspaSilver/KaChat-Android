package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRedactionTest {

    private val address = "kaspa:qypq0z8c7k3m9xw4fh2r5tj6yv8ln3dsa0gu7e2pzx9wq4kc"

    @Test
    fun `a full address is cut to its last 8 characters`() {
        assertEquals("Sending to …zx9wq4kc", shortenAddresses("Sending to $address"))
    }

    @Test
    fun `every address in a line is shortened, on every prefix`() {
        val testnet = "kaspatest:qqabcdefghijklmnopqrstuvwxyz0123456789"
        assertEquals(
            "from …zx9wq4kc to …23456789",
            shortenAddresses("from $address to $testnet")
        )
    }

    @Test
    fun `the underscore form used in keys and file names is shortened`() {
        assertEquals(
            "file …zx9wq4kc.json",
            shortenAddresses("file " + address.replace(':', '_') + ".json")
        )
    }

    @Test
    fun `an address inside a URL is shortened`() {
        assertEquals(
            "HTTP 500 GET https://api.kaspa.org/addresses/…zx9wq4kc/balance in 12ms",
            shortenAddresses("HTTP 500 GET https://api.kaspa.org/addresses/$address/balance in 12ms")
        )
        // percent-encoded colon: not the pattern, left alone as iOS leaves it
        val encoded = "https://api.kaspa.org/addresses/kaspa%3Aqypq0z8c7k3m9xw4/balance"
        assertEquals(encoded, shortenAddresses(encoded))
    }

    @Test
    fun `upper case matches too`() {
        assertEquals("…ZX9WQ4KC", shortenAddresses(address.uppercase()))
    }

    @Test
    fun `short payloads and other text are left alone`() {
        listOf(
            "kaspa:abc",
            "kaspa:qypq0z8c7k3",           // 11 characters after the prefix
            "node kaspa.example.org:16110",
            "no addresses here",
            ""
        ).forEach { assertEquals(it, shortenAddresses(it)) }
    }

    @Test
    fun `twelve characters is enough`() {
        assertEquals("…0z8c7k3m", shortenAddresses("kaspa:qypq0z8c7k3m"))
    }
}
