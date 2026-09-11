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

    @Test
    fun buildsExitRequestUrlFromOlcSubAndDrink() {
        assertEquals(
            "https://olcsub.ptrkkvn.beer/abc123/exit",
            PtrkSubscriptionCompanion.exitRequestUrl("https://olcsub.ptrkkvn.beer/abc123"),
        )
        assertEquals(
            "https://olcsub.ptrkkvn.beer/abc123/exit",
            PtrkSubscriptionCompanion.exitRequestUrl("https://drink.ptrkkvn.beer/mug/abc123"),
        )
        assertEquals(
            "https://olcsub.ptrkkvn.beer/abc123/exit",
            PtrkSubscriptionCompanion.exitRequestUrl("https://olcsub.ptrkkvn.beer/abc123/exit"),
        )
        assertNull(PtrkSubscriptionCompanion.exitRequestUrl("https://example.com/sub"))
    }

    @Test
    fun normalizesAllowedExits() {
        assertEquals("pl", PtrkSubscriptionCompanion.normalizeExitCountry("PL"))
        assertEquals("de", PtrkSubscriptionCompanion.normalizeExitCountry(" de "))
        assertNull(PtrkSubscriptionCompanion.normalizeExitCountry("us"))
        assertNull(PtrkSubscriptionCompanion.normalizeExitCountry(""))
    }
}
