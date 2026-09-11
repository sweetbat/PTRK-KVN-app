package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuSafeDnsTest {
    @Test
    fun rejectsGoogleAndCloudflare() {
        assertTrue(RuSafeDns.isHijacked("8.8.8.8"))
        assertTrue(RuSafeDns.isHijacked("8.8.4.4"))
        assertTrue(RuSafeDns.isHijacked("1.1.1.1"))
        assertTrue(RuSafeDns.isHijacked("1.0.0.1"))
        assertTrue(RuSafeDns.isHijacked("8.8.8.8:53"))
        assertTrue(RuSafeDns.isHijacked("2001:4860:4860::8888"))
        assertTrue(RuSafeDns.isHijacked("2606:4700:4700::1111"))
    }

    @Test
    fun keepsYandexAndCarrier() {
        assertFalse(RuSafeDns.isHijacked("77.88.8.8"))
        assertFalse(RuSafeDns.isHijacked("94.140.14.14"))
        assertFalse(RuSafeDns.isHijacked("192.168.1.1"))
        assertFalse(RuSafeDns.isHijacked("10.0.0.1"))
    }

    @Test
    fun sanitizeDropsHijackedAndKeepsRest() {
        val out = RuSafeDns.sanitize(listOf("8.8.8.8", "77.88.8.8", "1.1.1.1", "192.168.10.1"))
        assertEquals(listOf("77.88.8.8", "192.168.10.1"), out)
    }

    @Test
    fun plainBootstrapNeverIncludesGoogleOrCloudflare() {
        val out = RuSafeDns.plainBootstrap(listOf("8.8.8.8", "1.1.1.1"))
        assertFalse(out.any { RuSafeDns.isHijacked(it) })
        assertTrue("77.88.8.8" in out)
    }
}
