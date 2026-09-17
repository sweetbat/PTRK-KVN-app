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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

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
            modifier = Modifier.weight(1f, fill = true)
        ) {
            AutoShrinkText(
                text = cleanName,
                color = textColor,
                maxFontSize = nameFontSize,
                minFontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
            )

            val protocolTags = metadata?.protocolTags().orEmpty()
            if (protocolTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                ProtocolTagRow(
                    tags = protocolTags,
                    maxFontSize = tagFontSize,
                    darkTheme = darkTheme,
                )
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
        val maxWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        // Leave a few px so glyphs are not clipped at the edge.
        val budget = (maxWidthPx - 4f).coerceAtLeast(1f)
        val fitted = remember(text, budget, maxFontSize, minFontSize, fontWeight) {
            var lo = minFontSize.value
            var hi = maxFontSize.value
            var best = minFontSize
            repeat(16) {
                val mid = (lo + hi) / 2f
                val result = measurer.measure(
                    text = AnnotatedString(text),
                    style = TextStyle(fontSize = mid.sp, fontWeight = fontWeight),
                    maxLines = 1,
                    softWrap = false,
                )
                if (result.size.width <= budget) {
                    best = mid.sp
                    lo = mid
                } else {
                    hi = mid
                }
            }
            best
        }
        var liveSize by remember(text, fitted) { mutableStateOf(fitted) }
        Text(
            text = text,
            color = color,
            fontSize = liveSize,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = { layout ->
                if (layout.hasVisualOverflow && liveSize.value > minFontSize.value + 0.25f) {
                    liveSize = (liveSize.value - 0.5f).coerceAtLeast(minFontSize.value).sp
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProtocolTagRow(
    tags: List<String>,
    maxFontSize: TextUnit = 9.sp,
    darkTheme: Boolean,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val maxWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val budget = (maxWidthPx - 6f).coerceAtLeast(1f)
        val candidates = tags.take(6)

        data class Fit(val tags: List<String>, val size: TextUnit, val hPadDp: Float)

        val fit = remember(candidates, budget, maxFontSize, darkTheme) {
            val dropOrder = listOf("JSON", "VISION", "NONE", "UDP", "HTTP", "H2")
            var working = candidates.toMutableList()
            fun measure(sizeSp: Float, hPadDp: Float, list: List<String>): Float {
                var total = 0f
                val gapPx = with(density) { 4.dp.toPx() }
                val hPadPx = with(density) { (hPadDp * 2).dp.toPx() }
                list.forEachIndexed { index, tag ->
                    val result = measurer.measure(
                        text = AnnotatedString(tag),
                        style = TextStyle(fontSize = sizeSp.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        softWrap = false,
                    )
                    total += result.size.width + hPadPx
                    if (index > 0) total += gapPx
                }
                return total
            }
            fun bestFor(list: List<String>): Fit? {
                for (hPad in listOf(5f, 4f, 3f)) {
                    var lo = 6f
                    var hi = maxFontSize.value
                    var best: TextUnit? = null
                    repeat(14) {
                        val mid = (lo + hi) / 2f
                        if (measure(mid, hPad, list) <= budget) {
                            best = mid.sp
                            lo = mid
                        } else {
                            hi = mid
                        }
                    }
                    if (best != null) return Fit(list, best!!, hPad)
                }
                return null
            }
            var result = bestFor(working)
            while (result == null && working.size > 1) {
                val drop = dropOrder.firstOrNull { it in working } ?: working.last()
                working.remove(drop)
                result = bestFor(working)
            }
            result ?: Fit(working.take(1), 6.sp, 3f)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            fit.tags.forEach { tag ->
                val colors = protocolTagColors(tag, darkTheme)
                Text(
                    text = tag,
                    color = colors.first,
                    fontSize = fit.size,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.second)
                        .padding(horizontal = fit.hPadDp.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Soft unique pastels per protocol/transport/security/JSON.
 * Light theme: muted text on pastel bg. Dark theme: pastel text on deep muted bg.
 */
private fun protocolTagColors(tag: String, darkTheme: Boolean): Pair<Color, Color> {
    val key = tag.trim().uppercase()
    // textLight, bgLight, textDark, bgDark
    val palette = when (key) {
        "VLESS" -> Quad(0xFF5A6FA8, 0xFFE8ECF8, 0xFFB8C6F0, 0xFF2A3148)
        "VMESS" -> Quad(0xFF6B7DB5, 0xFFEEF0FA, 0xFFC0CAF0, 0xFF2C334A)
        "TROJAN" -> Quad(0xFF8B6FA8, 0xFFF3EAF8, 0xFFD2B8E8, 0xFF352A42)
        "SS", "SHADOWSOCKS" -> Quad(0xFFB07A5A, 0xFFF8EDE4, 0xFFE8C4A8, 0xFF3A2E28)
        "HYSTERIA2", "HYSTERIA", "HY2" -> Quad(0xFF4A9A8A, 0xFFE4F5F1, 0xFF9AD8C8, 0xFF243832)
        "TUIC" -> Quad(0xFF5A9A6E, 0xFFE6F5EB, 0xFFA8D8B8, 0xFF283828)
        "TCP" -> Quad(0xFF7A8494, 0xFFEEF1F4, 0xFFB0B8C4, 0xFF2C3038)
        "XHTTP" -> Quad(0xFFA89050, 0xFFF7F1DE, 0xFFE0D0A0, 0xFF383228)
        "WS", "WEBSOCKET" -> Quad(0xFF4A9AB8, 0xFFE3F3F8, 0xFF9AD0E0, 0xFF243840)
        "GRPC" -> Quad(0xFF7A9A5A, 0xFFEEF5E4, 0xFFC0D8A0, 0xFF303828)
        "H2", "HTTP", "HTTPUPGRADE" -> Quad(0xFF6A8A9A, 0xFFE8F0F4, 0xFFA8C4D0, 0xFF283038)
        "UDP" -> Quad(0xFF8A7A6A, 0xFFF4EEE8, 0xFFD0C0B0, 0xFF342E28)
        "NONE" -> Quad(0xFFB07088, 0xFFF8E8EE, 0xFFE8B0C0, 0xFF3A2830)
        "REALITY" -> Quad(0xFF6A70B0, 0xFFEAEBFA, 0xFFB8BCE8, 0xFF2A2C48)
        "TLS" -> Quad(0xFF4A9A78, 0xFFE4F6EE, 0xFF9AD8B8, 0xFF243830)
        "VISION" -> Quad(0xFF5A8A9A, 0xFFE6F2F6, 0xFFA8CCD8, 0xFF283438)
        "JSON" -> Quad(0xFFB87868, 0xFFF8EBE6, 0xFFE8B8A8, 0xFF3A2C28)
        else -> Quad(0xFF7A8490, 0xFFEFF1F3, 0xFFB0B8C0, 0xFF2C3034)
    }
    return if (darkTheme) {
        Color(palette.textDark) to Color(palette.bgDark)
    } else {
        Color(palette.textLight) to Color(palette.bgLight)
    }
}

private data class Quad(
    val textLight: Long,
    val bgLight: Long,
    val textDark: Long,
    val bgDark: Long,
)

private fun quotaText(used: String?, available: String?): String? {
    val usedRaw = used?.trim()?.takeIf { it.isNotBlank() } ?: return available?.let {
        org.olcbox.app.i18n.S.localizeDataUnit(it)
    }
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
