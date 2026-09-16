package org.olcbox.app.ui.features.locations.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.parseTrafficQuota
import org.olcbox.app.i18n.AppLocale
import org.olcbox.app.i18n.S
import org.olcbox.app.ui.components.TrafficQuotaIndicator
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.util.normalizeFlagToken
import org.olcbox.app.util.parseEmojiAndName

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocationRow(
    location: LocationItem,
    isSelected: Boolean,
    isLoading: Boolean,
    pingMs: Int?,
    isError: Boolean = false,
    settingsEnabled: Boolean = true,
    nameFontSize: TextUnit = 16.sp,
    tagFontSize: TextUnit = 9.sp,
    onSettingsClick: () -> Unit = {},
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "locationRowContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "locationRowBorder"
    )
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val textColor = MaterialTheme.colorScheme.onSurface

    val metadata = location.metadata
    val rawName = metadata?.name?.takeIf { it.isNotBlank() } ?: location.fullName
    val fallbackIcon = metadata?.icon?.takeIf { it.isNotBlank() }
        ?: metadata?.subscription?.icon?.takeIf { it.isNotBlank() }
        ?: ""
    val (emojiRaw, parsedName) = parseEmojiAndName(rawName, fallbackIcon)
    val emoji = normalizeFlagToken(emojiRaw)
    val cleanName = parsedName.ifBlank { location.config?.displayName().orEmpty() }
        .removePrefix("Olc ")
        .removePrefix("olc ")
        .trim()
    val description = metadata?.displayDescription()
    @Suppress("UNUSED_VARIABLE")
    val language = AppLocale.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (settingsEnabled) onSettingsClick()
                }
            )
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (emoji.isNotEmpty()) {
            Text(
                text = emoji,
                fontSize = 26.sp,
                fontFamily = FontFamily.Default,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            AutoShrinkText(
                text = cleanName,
                color = textColor,
                maxFontSize = nameFontSize,
                minFontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
            )

            val protocolTags = metadata?.protocolTags().orEmpty()
            if (protocolTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                ProtocolTagRow(tags = protocolTags, maxFontSize = tagFontSize)
            } else if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (protocolTags.isEmpty()) {
                Text(
                    text = locationSubtitle(location),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            TrafficQuotaIndicator(
                used = metadata?.used,
                available = metadata?.available,
                modifier = Modifier.padding(top = 6.dp),
                compact = true
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier.width(48.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            when {
                isLoading -> {
                    ShimmeringPingSkeleton()
                }

                pingMs != null -> {
                    Text(
                        text = "$pingMs ${org.olcbox.app.i18n.S.localizeDataUnit("ms")}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                isError -> {
                    Text(
                        text = S.offline,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        if (settingsEnabled) {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        LocationSelectionIndicator(isSelected = isSelected)
    }
}

private fun locationSubtitle(location: LocationItem): String {
    val config = location.config
    val metadata = location.metadata
    if (config?.isMihomo() == true) {
        val tags = metadata?.protocolTags().orEmpty()
        if (tags.isNotEmpty()) return tags.joinToString(" · ")
        return S.mihomoEngine
    }

    val providerName = config?.providerName()
        ?: LocationConfig.providerDisplayName(LocationConfig.DEFAULT_BYPASS_PROVIDER)
    val transportName = config?.transportName()
        ?: LocationConfig.transportDisplayName(LocationConfig.DEFAULT_TRANSPORT)

    return listOfNotNull(
        providerName,
        transportName,
        metadata?.ip?.takeIf { it.isNotBlank() }?.let { "IP $it" },
        quotaText(metadata?.used, metadata?.available)
            .takeUnless { parseTrafficQuota(metadata?.used, metadata?.available) != null }
    ).joinToString(" · ")
}

@Composable
private fun AutoShrinkText(
    text: String,
    color: Color,
    maxFontSize: TextUnit,
    minFontSize: TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val fitted = remember(text, maxWidthPx, maxFontSize, minFontSize, fontWeight) {
            var lo = minFontSize.value
            var hi = maxFontSize.value
            var best = minFontSize
            repeat(14) {
                val mid = (lo + hi) / 2f
                val result = measurer.measure(
                    text = AnnotatedString(text),
                    style = TextStyle(fontSize = mid.sp, fontWeight = fontWeight),
                    maxLines = 1,
                    softWrap = false,
                )
                if (result.size.width <= maxWidthPx) {
                    best = mid.sp
                    lo = mid
                } else {
                    hi = mid
                }
            }
            best
        }
        Text(
            text = text,
            color = color,
            fontSize = fitted,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun ProtocolTagRow(
    tags: List<String>,
    maxFontSize: TextUnit = 9.sp,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val displayTags = tags.take(6)
        val fitted = remember(displayTags, maxWidthPx, maxFontSize) {
            val minSp = 6f
            var lo = minSp
            var hi = maxFontSize.value
            var best = minSp.sp
            fun fits(sizeSp: Float): Boolean {
                var total = 0f
                val gapPx = with(density) { 4.dp.toPx() }
                val hPadPx = with(density) { 10.dp.toPx() } // 5dp * 2
                displayTags.forEachIndexed { index, tag ->
                    val result = measurer.measure(
                        text = AnnotatedString(tag),
                        style = TextStyle(fontSize = sizeSp.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        softWrap = false,
                    )
                    total += result.size.width + hPadPx
                    if (index > 0) total += gapPx
                }
                return total <= maxWidthPx
            }
            repeat(14) {
                val mid = (lo + hi) / 2f
                if (fits(mid)) {
                    best = mid.sp
                    lo = mid
                } else {
                    hi = mid
                }
            }
            best
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            displayTags.forEach { tag ->
                val colors = protocolTagColors(tag)
                Text(
                    text = tag,
                    color = colors.first,
                    fontSize = fitted,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.second)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Soft unique pastels: protocol / transport / security / JSON — no shared hues.
 * Pair = (text, background).
 */
private fun protocolTagColors(tag: String): Pair<Color, Color> {
    val key = tag.trim().uppercase()
    return when (key) {
        // Protocols
        "VLESS" -> Color(0xFF5A6FA8) to Color(0xFFE8ECF8)          // periwinkle
        "VMESS" -> Color(0xFF6B7DB5) to Color(0xFFEEF0FA)
        "TROJAN" -> Color(0xFF8B6FA8) to Color(0xFFF3EAF8)         // lilac
        "SS", "SHADOWSOCKS" -> Color(0xFFB07A5A) to Color(0xFFF8EDE4) // peach
        "HYSTERIA2", "HYSTERIA", "HY2" -> Color(0xFF4A9A8A) to Color(0xFFE4F5F1) // mint
        "TUIC" -> Color(0xFF5A9A6E) to Color(0xFFE6F5EB)
        // Transports — each distinct from protocols
        "TCP" -> Color(0xFF7A8494) to Color(0xFFEEF1F4)            // cool gray
        "XHTTP" -> Color(0xFFA89050) to Color(0xFFF7F1DE)          // butter
        "WS", "WEBSOCKET" -> Color(0xFF4A9AB8) to Color(0xFFE3F3F8) // sky
        "GRPC" -> Color(0xFF7A9A5A) to Color(0xFFEEF5E4)           // sage
        "H2", "HTTP", "HTTPUPGRADE" -> Color(0xFF6A8A9A) to Color(0xFFE8F0F4)
        "UDP" -> Color(0xFF8A7A6A) to Color(0xFFF4EEE8)
        // Security
        "NONE" -> Color(0xFFB07088) to Color(0xFFF8E8EE)           // rose
        "REALITY" -> Color(0xFF6A70B0) to Color(0xFFEAEBFA)        // soft indigo
        "TLS" -> Color(0xFF4A9A78) to Color(0xFFE4F6EE)            // seafoam
        "VISION" -> Color(0xFF5A8A9A) to Color(0xFFE6F2F6)
        // Format
        "JSON" -> Color(0xFFB87868) to Color(0xFFF8EBE6)           // coral
        else -> Color(0xFF7A8490) to Color(0xFFEFF1F3)
    }
}

private fun quotaText(used: String?, available: String?): String? {
    val usedRaw = used?.trim()?.takeIf { it.isNotBlank() } ?: return available?.let {
        org.olcbox.app.i18n.S.localizeDataUnit(it)
    }
    // "358mb/100gb" or "549.81GB"
    val usedPart = usedRaw.split('/', limit = 2).first().trim()
    val usedLabel = org.olcbox.app.i18n.S.localizeDataUnit(usedPart)
    val avail = available?.trim()?.takeIf { it.isNotBlank() }
    return when {
        avail != null && (avail == "∞" || avail == "\u221e" ||
            avail.equals("unlimited", true) ||
            Regex("""(?i)^0(\.0+)?\s*(b|kb|mb|gb|tb)?$""").matches(avail)) ->
            "$usedLabel/\u221e"
        !avail.isNullOrBlank() ->
            "$usedLabel/${org.olcbox.app.i18n.S.localizeDataUnit(avail)}"
        else -> usedLabel
    }
}

@Composable
private fun LocationSelectionIndicator(isSelected: Boolean) {
    if (isSelected) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = "Selected location",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    } else {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ShimmeringPingSkeleton() {
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim by transition.animateFloat(
        initialValue = -50f,
        targetValue = 150f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_anim"
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f),
        ),
        start = Offset(translateAnim, 0f),
        end = Offset(translateAnim + 50f, 50f)
    )

    Box(
        modifier = Modifier
            .width(42.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(brush)
    )
}
