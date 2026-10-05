package com.kachat.app.services.kachatnames

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The name detail's pure pieces: the Owner card's one-line address (iOS 71448d8
 * `KachatNamesRegistry.compactAddress`).
 */
class KachatNamesDetailTest {

    @Test
    fun compactAddressKeepsPrefixAndBothEnds() {
        val a = "kaspatest:qr4x7kabcdefghijklmnopqrstuvwxyz0123456789a9z2pq"
        assertEquals("kaspatest:qr4x7k...a9z2pq", KachatNamesRegistry.compactAddress(a))
        assertEquals("kaspa:qypq12...89abcd", KachatNamesRegistry.compactAddress("kaspa:qypq12xxxxxxxxxxxxxxx89abcd"))
    }

    @Test
    fun compactAddressLeavesShortOrPrefixlessAlone() {
        // no network prefix: as it is
        assertEquals("qr4x7kabcdefghijklmnop", KachatNamesRegistry.compactAddress("qr4x7kabcdefghijklmnop"))
        // a body of 14 characters or fewer: nothing to cut
        assertEquals("kaspatest:abcdefghijklmn", KachatNamesRegistry.compactAddress("kaspatest:abcdefghijklmn"))
        // 15: cut
        assertEquals("kaspatest:abcdef...jklmno", KachatNamesRegistry.compactAddress("kaspatest:abcdefghijklmno"))
        assertEquals("", KachatNamesRegistry.compactAddress(""))
    }
}
