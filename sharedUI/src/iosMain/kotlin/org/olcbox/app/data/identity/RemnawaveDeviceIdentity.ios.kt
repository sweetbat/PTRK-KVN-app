package org.olcbox.app.data.identity

import platform.UIKit.UIDevice

internal actual fun platformDeviceModel(): String =
    UIDevice.currentDevice.model.trim().ifBlank { "iPhone" }

internal actual fun platformOsVersion(): String =
    UIDevice.currentDevice.systemVersion.trim().ifBlank { "unknown" }
