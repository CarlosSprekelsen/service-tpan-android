package com.katim.dts.tpan.bearer

import com.katim.dts.tpan.provision.TpanBundle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for bearer priority selection logic extracted from [BearerMonitor].
 *
 * The selector filters disabled bearers and excludes USB (handled separately),
 * returning wireless bearers in priority order.
 */
class BearerSelectorTest {

    @Test
    fun `default policy returns bt only when wifi disabled`() {
        val policy = bearerPolicy(
            priority = listOf("usb", "uwb", "wifi", "bt"),
            disabled = listOf("wifi")
        )
        assertEquals(listOf("uwb", "bt"), selectBearers(policy))
    }

    @Test
    fun `all wireless disabled returns empty list`() {
        val policy = bearerPolicy(
            priority = listOf("usb", "uwb", "wifi", "bt"),
            disabled = listOf("uwb", "wifi", "bt")
        )
        assertEquals(emptyList<String>(), selectBearers(policy))
    }

    @Test
    fun `no bearers disabled returns all wireless in order`() {
        val policy = bearerPolicy(
            priority = listOf("usb", "uwb", "wifi", "bt"),
            disabled = emptyList()
        )
        assertEquals(listOf("uwb", "wifi", "bt"), selectBearers(policy))
    }

    @Test
    fun `custom priority order is preserved`() {
        val policy = bearerPolicy(
            priority = listOf("usb", "bt", "wifi", "uwb"),
            disabled = emptyList()
        )
        assertEquals(listOf("bt", "wifi", "uwb"), selectBearers(policy))
    }

    @Test
    fun `bt-only policy`() {
        val policy = bearerPolicy(
            priority = listOf("usb", "bt"),
            disabled = emptyList()
        )
        assertEquals(listOf("bt"), selectBearers(policy))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Replicate BearerMonitor.selectBearers logic for unit testing. */
    private fun selectBearers(policy: TpanBundle.BearerPolicy): List<String> {
        val disabled = policy.disabledBearers.toSet()
        return policy.priority
            .filter { it != "usb" }
            .filter { it !in disabled }
    }

    private fun bearerPolicy(
        priority: List<String>,
        disabled: List<String>
    ) = TpanBundle.BearerPolicy(
        priority = priority,
        disabledBearers = disabled,
        singleActiveBearer = true
    )
}
