package org.olcbox.app.ui.features.home.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.olcbox.app.i18n.AppLocale
import org.olcbox.app.i18n.S
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.olcbox.app.ui.features.locations.LocationItem

@Composable
fun PtrkBrandHeader() {
    val language by AppLocale.language.collectAsState()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "P",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
        Box(modifier = Modifier.height(10.dp))
        Text(
            text = S.appName,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Keep language in composition graph so labels refresh.
        @Suppress("UNUSED_EXPRESSION")
        language
    }
}

@Composable
fun SubscriptionCard(
    location: LocationItem?,
    onRefreshClick: (() -> Unit)? = null,
) {
    val sub = location?.metadata?.subscription
    val title = (sub?.name
        ?: location?.subscriptionUrl?.substringAfterLast('/')
        ?: location?.fullName
        ?: org.olcbox.app.i18n.S.noSubscription)
        .removePrefix("Olc ")
        .removePrefix("olc ")
        .trim()
        .ifBlank { org.olcbox.app.i18n.S.appName }
    val traffic = listOfNotNull(sub?.used, sub?.available)
        .takeIf { it.size == 2 }
        ?.let { "${it[0]} / ${it[1]}" }
        ?: sub?.used
    val expire = sub?.description ?: sub?.comment
    val engine = when {
        location?.config?.isMihomo() == true -> org.olcbox.app.i18n.S.mihomoEngine
        location != null -> org.olcbox.app.i18n.S.olcrtcEngine
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f),
                    )
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = org.olcbox.app.i18n.S.subscription,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (engine != null) {
                Text(
                    text = engine,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (!traffic.isNullOrBlank() || !expire.isNullOrBlank()) {
            Box(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!traffic.isNullOrBlank()) {
                    Column {
                        Text(
                            text = org.olcbox.app.i18n.S.traffic,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = traffic, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!expire.isNullOrBlank()) {
                    Column {
                        Text(
                            text = org.olcbox.app.i18n.S.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = expire, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (onRefreshClick != null && !location?.subscriptionUrl.isNullOrBlank()) {
            Box(modifier = Modifier.height(12.dp))
            Text(
                text = org.olcbox.app.i18n.S.updateSubscription,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onRefreshClick),
            )
        }
    }
}

@Composable
fun MihomoModeSelector(
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    val language by AppLocale.language.collectAsState()
    val modes = listOf(
        "rule" to S.modeRule,
        "global" to S.modeGlobal,
        "direct" to S.modeDirect,
    )
    @Suppress("UNUSED_EXPRESSION")
    language
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        modes.forEach { (value, label) ->
            val active = selected.equals(value, ignoreCase = true)
            val bg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "modeBg",
            )
            val fg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "modeFg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .clickable(enabled = enabled) { onSelected(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = fg,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun PtrkConnectButton(
    isActive: Boolean,
    isLoading: Boolean,
    requiresSetup: Boolean,
    uptimeText: String?,
    onClick: () -> Unit,
) {
    val pulse by animateFloatAsState(
        targetValue = if (isActive && !isLoading) 1.04f else 1f,
        animationSpec = tween(700),
        label = "pulse",
    )
    val ring by animateColorAsState(
        when {
            isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            requiresSetup -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        label = "ring",
    )
    val fill by animateColorAsState(
        when {
            isActive -> MaterialTheme.colorScheme.primary
            requiresSetup -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        label = "fill",
    )
    val content = when {
        isActive -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .scale(pulse)
                .background(ring, CircleShape)
                .padding(10.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(fill)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(190.dp),
                    color = content,
                    strokeWidth = 4.dp,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = content.copy(alpha = if (isLoading) 0.55f else 1f),
                    modifier = Modifier.size(52.dp),
                )
                Box(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        requiresSetup -> org.olcbox.app.i18n.S.setup
                        isLoading || isActive -> org.olcbox.app.i18n.S.stop
                        else -> org.olcbox.app.i18n.S.start
                    },
                    color = content,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
            }
        }
        Box(modifier = Modifier.height(12.dp))
        Text(
            text = uptimeText ?: if (isActive) org.olcbox.app.i18n.S.connected else org.olcbox.app.i18n.S.disconnected,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun rememberUptimeText(connectedSinceEpochMs: Long?): String? {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSinceEpochMs) {
        if (connectedSinceEpochMs == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val start = connectedSinceEpochMs ?: return null
    val totalSec = ((now - start).coerceAtLeast(0L) / 1000L).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    fun pad(v: Int) = v.toString().padStart(2, '0')
    return "${pad(h)}:${pad(m)}:${pad(s)}"
}
