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
        PtrkLogoImage(
            contentDescription = S.appName,
            modifier = Modifier.size(96.dp),
        )
        @Suppress("UNUSED_EXPRESSION")
        language
    }
}

@Composable
fun SubscriptionCard(
    location: LocationItem?,
    onRefreshClick: (() -> Unit)? = null,
) {
    val language by org.olcbox.app.i18n.AppLocale.language.collectAsState()
    @Suppress("UNUSED_EXPRESSION")
    language
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val sub = location?.metadata?.subscription
    val urlToken = location?.subscriptionUrl
        ?.substringAfterLast('/')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val rawTitle = sub?.name?.trim()?.takeIf { it.isNotBlank() }
        ?.takeUnless { it == urlToken }
    val title = (rawTitle
        ?: location?.fullName?.takeUnless { it == urlToken || it.equals("regular", true) || it.equals("bypass", true) }
        ?: org.olcbox.app.i18n.S.appName)
        .removePrefix("Olc ")
        .removePrefix("olc ")
        .trim()
        .ifBlank { org.olcbox.app.i18n.S.appName }
    val traffic = when {
        !sub?.used.isNullOrBlank() && !sub?.available.isNullOrBlank() ->
            org.olcbox.app.i18n.S.trafficSummary(sub!!.used!!, sub.available!!)
        !sub?.used.isNullOrBlank() -> org.olcbox.app.i18n.S.localizeDataUnit(sub!!.used!!)
        else -> null
    }
    val quota = org.olcbox.app.data.model.parseTrafficQuota(sub?.used, sub?.available)
    fun usableStatus(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (v.equals("regular", ignoreCase = true) || v.equals("bypass", ignoreCase = true)) {
            return null
        }
        return v
    }
    // Prefer explicit expire date; also accept values that look like dd.MM.yyyy.
    val expire = usableStatus(sub?.description)
        ?.takeIf { it == "\u221e" || it == "∞" || it.contains('.') || it.contains('-') || it.any { ch -> ch.isDigit() } }
        ?: usableStatus(sub?.comment)?.takeIf { it.contains('.') || it == "\u221e" }
    val announce = sub?.displayAnnounce()
    val supportUrl = sub?.supportUrl
    val webPageUrl = sub?.webPageUrl
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
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
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
                    text = org.olcbox.app.i18n.S.selectedSubscription,
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
        if (!traffic.isNullOrBlank() || quota != null) {
            Box(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = org.olcbox.app.i18n.S.traffic,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!traffic.isNullOrBlank()) {
                        Text(text = traffic, style = MaterialTheme.typography.bodySmall)
                    }
                }
                org.olcbox.app.ui.components.TrafficQuotaIndicator(
                    used = sub?.used,
                    available = sub?.available,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!announce.isNullOrBlank()) {
            Box(modifier = Modifier.height(10.dp))
            Text(
                text = announce,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!expire.isNullOrBlank()) {
            Box(modifier = Modifier.height(8.dp))
            Text(
                text = "${org.olcbox.app.i18n.S.subscriptionExpires}: $expire",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!supportUrl.isNullOrBlank() || !webPageUrl.isNullOrBlank()) {
            Box(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!supportUrl.isNullOrBlank()) {
                    Text(
                        text = org.olcbox.app.i18n.S.support,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable { runCatching { uriHandler.openUri(supportUrl) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (!webPageUrl.isNullOrBlank()) {
                    Text(
                        text = org.olcbox.app.i18n.S.website,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable { runCatching { uriHandler.openUri(webPageUrl) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
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
    showTitle: Boolean = true,
) {
    val language by AppLocale.language.collectAsState()
    // Routing on = Clash rule mode (RU whitelist DIRECT). Off = global (all via node).
    val modes = listOf(
        "rule" to S.modeRule,
        "global" to S.modeGlobal,
    )
    val normalized = when (selected.lowercase()) {
        "global", "direct" -> "global"
        else -> "rule"
    }
    @Suppress("UNUSED_EXPRESSION")
    language
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showTitle) {
            Text(
                text = S.routingTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            modes.forEach { (value, label) ->
                val active = normalized == value
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
}

@Composable
fun PtrkConnectButton(
    isActive: Boolean,
    isLoading: Boolean,
    requiresSetup: Boolean,
    uptimeText: String?,
    onClick: () -> Unit,
) {
    val language by AppLocale.language.collectAsState()
    @Suppress("UNUSED_EXPRESSION")
    language
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
