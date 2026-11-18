package com.kachat.app.services.grpc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only a plain host:port another node gossips is kept: gRPC throws building a channel for a
 *  malformed target, and any node can send one. */
class GossipedNodeAddressTest {

    @Test
    fun `well-formed peer addresses are kept`() {
        assertTrue(isGossipedNodeAddress("1.2.3.4:16110"))
        assertTrue(isGossipedNodeAddress("[2001:db8::1]:16110"))
        assertTrue(isGossipedNodeAddress("node-1.example.org:443"))
    }

    @Test
    fun `malformed peer addresses are dropped`() {
        assertFalse(isGossipedNodeAddress("a b:1"))
        assertFalse(isGossipedNodeAddress("2001:db8::1:16110"))
        assertFalse(isGossipedNodeAddress("1.2.3.4"))
        assertFalse(isGossipedNodeAddress("1.2.3.4:0"))
        assertFalse(isGossipedNodeAddress("1.2.3.4:70000"))
        assertFalse(isGossipedNodeAddress("1.2.3.4:+80"))
        assertFalse(isGossipedNodeAddress(":16110"))
        assertFalse(isGossipedNodeAddress("[zz]:16110"))
        assertFalse(isGossipedNodeAddress("host/path:80"))
        assertFalse(isGossipedNodeAddress("grpc://1.2.3.4:16110"))
        assertFalse(isGossipedNodeAddress(""))
    }
}
