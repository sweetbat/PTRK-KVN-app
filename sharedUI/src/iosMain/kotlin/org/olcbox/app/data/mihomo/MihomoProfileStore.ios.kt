package org.olcbox.app.data.mihomo

import org.olcbox.app.data.model.ClashYaml

actual fun persistMihomoProfileYaml(profileId: String, yaml: String): String? = null

actual fun mihomoProfilePath(profileId: String): String? = null

actual suspend fun resolveMihomoLeafNodes(
    profileId: String,
    yamlFallback: ClashYaml.ExtractedNodes,
): ClashYaml.ExtractedNodes = yamlFallback
