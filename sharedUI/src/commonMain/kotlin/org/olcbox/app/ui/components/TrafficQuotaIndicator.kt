package org.olcbox.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.parseTrafficQuota

@Composable
fun TrafficQuotaIndicator(
    used: String?,
    available: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val quota = remember(used, available) { parseTrafficQuota(used, available) } ?: return
    val summary = org.olcbox.app.i18n.S.trafficUsedOfTotal(
        org.olcbox.app.i18n.S.localizeDataUnit(quota.usedLabel),
        org.olcbox.app.i18n.S.localizeDataUnit(quota.totalLabel),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp)
    ) {
        Text(
            text = summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = if (compact) 11.sp else 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        val usedFraction = (quota.usedBytes / quota.totalBytes)
            .toFloat()
            .coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { usedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 4.dp else 6.dp)
                .clip(RoundedCornerShape(99.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
