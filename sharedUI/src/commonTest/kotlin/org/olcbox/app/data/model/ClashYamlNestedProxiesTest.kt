package org.olcbox.app.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClashYamlNestedProxiesTest {
    @Test
    fun extractsTopLevelProxiesNotNestedGroupLists() {
        val yaml = """
            mixed-port: 7890
            mode: rule
            proxy-groups:
              - name: Select
                type: select
                proxies:
                  - Leaf One
                  - Leaf Two
              - name: Bypass
                type: select
                filter: (bypass)
                proxies:
                  - Auto
            proxies:
              - name: Leaf One
                type: vless
                server: a.example
                port: 443
              - name: Leaf Two
                type: vless
                server: b.example
                port: 443
              - name: Leaf Bypass
                type: vless
                server: c.example
                port: 443
        """.trimIndent()

        assertTrue(ClashYaml.looksLikeClash(yaml))
        val nodes = ClashYaml.extractNodes(yaml)
        assertTrue(
            nodes.all.containsAll(listOf("Leaf One", "Leaf Two", "Leaf Bypass")),
            "all=${nodes.all} regular=${nodes.regular} bypass=${nodes.bypass}",
        )
        assertTrue("Select" !in nodes.all)
        assertTrue("Bypass" !in nodes.all)
    }
}
