package org.olcbox.app.data.mihomo

import org.olcbox.app.data.model.ClashYaml
import java.io.File

private fun profilesRoot(): File {
    val home = System.getProperty("user.home") ?: "."
    return File(home, ".ptrkkvn/mihomo/profiles").apply { mkdirs() }
}

actual fun persistMihomoProfileYaml(profileId: String, yaml: String): String? {
    val id = profileId.trim().ifBlank { return null }
    val file = File(profilesRoot(), "$id.yaml")
    return runCatching {
        file.writeText(ClashYaml.decode(yaml))
        file.absolutePath
    }.getOrNull()
}

actual fun mihomoProfilePath(profileId: String): String? {
    val id = profileId.trim().ifBlank { return null }
    val file = File(profilesRoot(), "$id.yaml")
    return file.takeIf { it.exists() }?.absolutePath
}

actual suspend fun resolveMihomoLeafNodes(
    profileId: String,
    yamlFallback: ClashYaml.ExtractedNodes,
): ClashYaml.ExtractedNodes = yamlFallback

