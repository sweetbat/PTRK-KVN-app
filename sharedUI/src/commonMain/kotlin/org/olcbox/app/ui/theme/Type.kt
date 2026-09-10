package org.olcbox.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

@Composable
fun getAppTypography(): Typography {
    // Custom Compose Multiplatform fonts are not reliably packaged into the Android APK
    // in this project setup; use Material3 defaults so startup never crashes.
    return Typography()
}
