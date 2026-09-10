package org.olcbox.app.data.mihomo

/** Persist Clash YAML for later Mihomo setupConfig(config-path=...). */
expect fun persistMihomoProfileYaml(profileId: String, yaml: String): String?

expect fun mihomoProfilePath(profileId: String): String?

/**
 * Optionally refine leaf proxy lists after YAML is on disk (Android loads via libclash).
 * Other platforms return [yamlFallback] unchanged.
 */
expect suspend fun resolveMihomoLeafNodes(
    profileId: String,
    yamlFallback: org.olcbox.app.data.model.ClashYaml.ExtractedNodes,
): org.olcbox.app.data.model.ClashYaml.ExtractedNodes
