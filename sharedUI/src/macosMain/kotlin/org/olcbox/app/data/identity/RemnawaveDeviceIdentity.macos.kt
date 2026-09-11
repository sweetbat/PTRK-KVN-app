package org.olcbox.app.data.identity

import platform.Foundation.NSProcessInfo

internal actual fun platformDeviceModel(): String = "Mac"

internal actual fun platformOsVersion(): String =
    NSProcessInfo.processInfo.operatingSystemVersionString
        .trim()
        .ifBlank { "unknown" }
