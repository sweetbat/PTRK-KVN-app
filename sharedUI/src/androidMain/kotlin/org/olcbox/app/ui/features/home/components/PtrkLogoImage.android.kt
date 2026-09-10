package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight

@Composable
actual fun PtrkLogoImage(
    modifier: Modifier,
    contentDescription: String?,
) {
    val context = LocalContext.current
    val logoId = remember(context.packageName) {
        context.resources.getIdentifier("ic_ptrk_logo", "drawable", context.packageName)
    }
    if (logoId != 0) {
        Image(
            painter = painterResource(logoId),
            contentDescription = contentDescription,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "P",
                color = Color(0xFFC4784A),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
