package org.olcbox.app.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PtrkSubscriptionCompanionTest {
    @Test
    fun mapsDrinkMugToOlcSub() {
        assertEquals(
            "https://olcsub.ptrkkvn.beer/J5HobWHzAwAcdcrr",
            PtrkSubscriptionCompanion.olcRtcCompanionUrl(
                "https://drink.ptrkkvn.beer/mug/J5HobWHzAwAcdcrr",
            ),
        )
        assertEquals(
            "https://olcsub.ptrkkvn.beer/J5HobWHzAwAcdcrr",
            PtrkSubscriptionCompanion.olcRtcCompanionUrl(
                "https://drink.ptrkkvn.beer/mug/J5HobWHzAwAcdcrr/",
            ),
        )
    }

    @Test
    fun ignoresNonDrinkUrls() {
        assertNull(
            PtrkSubscriptionCompanion.olcRtcCompanionUrl(
                "https://olcsub.ptrkkvn.beer/J5HobWHzAwAcdcrr",
            ),
        )
        assertNull(PtrkSubscriptionCompanion.olcRtcCompanionUrl("https://example.com/mug/x"))
        assertTrue(PtrkSubscriptionCompanion.isOlcSubUrl("https://olcsub.ptrkkvn.beer/abc"))
    }
}
