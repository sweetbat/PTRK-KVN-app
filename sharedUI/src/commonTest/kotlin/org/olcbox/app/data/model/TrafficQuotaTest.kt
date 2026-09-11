package org.olcbox.app.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrafficQuotaTest {
    @Test
    fun parsesUsedTotalSlashForm() {
        val quota = assertNotNull(parseTrafficQuota("500mb/10gb", null))

        assertEquals("500mb", quota.usedLabel)
        assertEquals("10gb", quota.totalLabel)
        assertEquals(0.95f, quota.remainingFraction, absoluteTolerance = 0.001f)
    }

    @Test
    fun treatsSeparateAvailableAsPlanTotal() {
        // Remnawave subscription-userinfo: available is plan total, not remaining.
        val quota = assertNotNull(parseTrafficQuota("2.8MB", "10.0GB"))

        assertEquals("2.8MB", quota.usedLabel)
        assertEquals("10.0GB", quota.totalLabel)
        assertEquals(2.8 / (10.0 * 1024), quota.usedBytes / quota.totalBytes, absoluteTolerance = 0.0001)
    }

    @Test
    fun derivesAvailableFromUsedAndTotal() {
        val quota = assertNotNull(parseTrafficQuota("2 GB / 8 GB", null))

        assertEquals(0.75f, quota.remainingFraction, absoluteTolerance = 0.001f)
        assertEquals("8 GB", quota.totalLabel)
    }

    @Test
    fun rejectsQuotaWithoutEnoughInformation() {
        assertNull(parseTrafficQuota(null, "3gb"))
        assertNull(parseTrafficQuota("unlimited", "3gb"))
    }

    @Test
    fun descriptionTakesPriorityAndCommentRemainsCompatible() {
        assertEquals(
            "Preferred description",
            SubscriptionMetadata(
                description = "Preferred description",
                comment = "Legacy comment"
            ).displayDescription()
        )
        assertEquals(
            "Legacy comment",
            LocationMetadata(comment = "Legacy comment").displayDescription()
        )
    }
}
