package org.olcbox.app.data.identity

internal actual fun platformDeviceModel(): String = "Desktop"

internal actual fun platformOsVersion(): String =
    System.getProperty("os.name")?.trim()?.ifBlank { null } ?: "unknown"
