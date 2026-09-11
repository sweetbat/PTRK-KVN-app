package org.olcbox.app.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubscriptionDeepLinkTest {
    @Test
    fun parsesHappStyleAddLink() {
        assertEquals(
            "https://drink.ptrkkvn.beer/mug/J5HobWHzAwAcdcrr",
            SubscriptionDeepLink.extractSubscriptionUrl(
                "ptrkkvn://add/https://drink.ptrkkvn.beer/mug/J5HobWHzAwAcdcrr",
            ),
        )
    }

    @Test
    fun parsesEncodedInstallConfig() {
        assertEquals(
            "https://drink.ptrkkvn.beer/mug/abc",
            SubscriptionDeepLink.extractSubscriptionUrl(
                "ptrkkvn://install-config?url=https%3A%2F%2Fdrink.ptrkkvn.beer%2Fmug%2Fabc",
            ),
        )
    }

    @Test
    fun rejectsUnknownSchemes() {
        assertNull(SubscriptionDeepLink.extractSubscriptionUrl("happ://add/https://example.com/x"))
        assertNull(SubscriptionDeepLink.extractSubscriptionUrl("ptrkkvn://add/not-a-url"))
    }
}
