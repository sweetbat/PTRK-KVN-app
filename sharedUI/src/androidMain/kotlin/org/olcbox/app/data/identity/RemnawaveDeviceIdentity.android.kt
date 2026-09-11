package org.olcbox.app.data.identity

import android.os.Build

internal actual fun platformDeviceModel(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    if (model.isBlank() && manufacturer.isBlank()) return "Android"
    if (model.isBlank()) return manufacturer
    if (manufacturer.isBlank()) return model
    // Avoid "Samsung Samsung SM-…" / "Xiaomi Xiaomi 14"
    return if (model.startsWith(manufacturer, ignoreCase = true)) model
    else "$manufacturer $model"
}

internal actual fun platformOsVersion(): String =
    Build.VERSION.RELEASE?.trim()?.takeIf { it.isNotBlank() } ?: "unknown"
