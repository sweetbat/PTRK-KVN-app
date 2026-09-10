package org.olcbox.app.data.mihomo

import android.content.Context
import android.util.Log
import org.olcbox.app.data.model.ClashYaml
import java.io.File

/**
 * Application context is set from App.onCreate. Falls back to null persist if unset.
 */
object MihomoAndroidContext {
    @Volatile var app: Context? = null
}

actual fun persistMihomoProfileYaml(profileId: String, yaml: String): String? {
    val ctx = MihomoAndroidContext.app ?: return null
    val id = profileId.trim().ifBlank { return null }
    val dir = File(ctx.filesDir, "mihomo/profiles").apply { mkdirs() }
    val file = File(dir, "$id.yaml")
    return runCatching {
        file.writeText(ClashYaml.decode(yaml))
        file.absolutePath
    }.getOrNull()
}

actual fun mihomoProfilePath(profileId: String): String? {
    val ctx = MihomoAndroidContext.app ?: return null
    val id = profileId.trim().ifBlank { return null }
    val file = File(ctx.filesDir, "mihomo/profiles/$id.yaml")
    return file.takeIf { it.exists() }?.absolutePath
}

/**
 * Do NOT load libclash during import — it shares a Go runtime with libgojni (olcRTC)
 * and loading both in the UI process crashes. Leaf lists come from YAML parsing only.
 */
actual suspend fun resolveMihomoLeafNodes(
    profileId: String,
    yamlFallback: ClashYaml.ExtractedNodes,
): ClashYaml.ExtractedNodes {
    Log.i(
        "MihomoProfileStore",
        "resolveMihomoLeafNodes: yaml-only regular=${yamlFallback.regular.size} bypass=${yamlFallback.bypass.size}"
    )
    return yamlFallback
}
