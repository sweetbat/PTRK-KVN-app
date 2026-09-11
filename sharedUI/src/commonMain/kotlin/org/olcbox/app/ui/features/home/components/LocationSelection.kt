package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.parseTrafficQuota
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.features.locations.components.LocationRow
import org.olcbox.app.ui.features.locations.components.RefreshButton
import org.olcbox.app.ui.components.TrafficQuotaIndicator
import androidx.compose.ui.platform.UriHandler

@Composable
fun LocationSelectorScreen(
    modifier: Modifier = Modifier,
    onRefreshClick: (targetLocationIds: List<String>) -> Unit,
    onSubscriptionUpdateClick: (subscriptionUrl: String) -> Unit,
    updatingSubscriptionUrl: String?,
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    locations: List<LocationItem>,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val subscriptionLocations = locations.filter { !it.subscriptionUrl.isNullOrBlank() }
        val subscriptionGroups = subscriptionLocations
            .groupBy { it.subscriptionGroupKey() }
            .values
            .toList()
        val customLocations = locations.filter { it.subscriptionUrl.isNullOrBlank() }

        if (locations.isEmpty()) {
            RelaySetupCard(
                onAddSubscriptionClick = onAddSubscriptionClick,
                onAddLocationClick = onAddLocationClick
            )
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            subscriptionGroups.forEach { group ->
                val subscription = group
                    .mapNotNull { it.metadata?.subscription }
                    .firstOrNull {
                        !it.announce.isNullOrBlank() ||
                            !it.used.isNullOrBlank() ||
                            !it.supportUrl.isNullOrBlank() ||
                            !it.webPageUrl.isNullOrBlank() ||
                            !it.description.isNullOrBlank() ||
                            !it.name.isNullOrBlank()
                    }
                    ?: group.firstOrNull()?.metadata?.subscription
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SubscriptionGroupHeader(
                                locations = group,
                                modifier = Modifier.weight(1f)
                            )

                            val groupIds = group.map { it.storageId }
                            val isGroupRefreshing = pingsState is PingsState.Loading &&
                                    pingsState.pendingLocationIds.any { it in groupIds }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RefreshButton(
                                    isRefreshing = isGroupRefreshing,
                                    onClick = { onRefreshClick(groupIds) },
                                    tint = MaterialTheme.colorScheme.primary,
                                    label = org.olcbox.app.i18n.S.ping,
                                    icon = Icons.Outlined.Bolt,
                                    enabled = updatingSubscriptionUrl == null
                                )

                                group.firstOrNull()
                                    ?.subscriptionUrl
                                    ?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { subscriptionUrl ->
                                        RefreshButton(
                                            isRefreshing = updatingSubscriptionUrl == subscriptionUrl,
                                            onClick = { onSubscriptionUpdateClick(subscriptionUrl) },
                                            tint = MaterialTheme.colorScheme.primary,
                                            label = org.olcbox.app.i18n.S.update,
                                            icon = Icons.Outlined.Refresh,
                                            enabled = updatingSubscriptionUrl == null && !isGroupRefreshing
                                        )
                                    }
                            }
                        }

                        SubscriptionInfoPanel(
                            subscription = subscription,
                            uriHandler = uriHandler,
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            group.forEach { location ->
                                LocationSelectorRow(
                                    location = location,
                                    selectedLocationId = selectedLocationId,
                                    pingsState = pingsState,
                                    onLocationSelected = onLocationSelected,
                                    onLocationSettingsClick = onLocationSettingsClick
                                )
                            }
                        }
                    }
                }
            }

            if (customLocations.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LocationGroupHeader(
                            title = "Custom locations",
                            modifier = Modifier.weight(1f)
                        )

                        // 2. Вычисляем состояние загрузки только для кастомных локаций
                        val customIds = customLocations.map { it.storageId }
                        val isCustomRefreshing = pingsState is PingsState.Loading &&
                                pingsState.pendingLocationIds.any { it in customIds }

                        RefreshButton(
                            isRefreshing = isCustomRefreshing,
                            onClick = { onRefreshClick(customIds) },
                            tint = MaterialTheme.colorScheme.primary,
                            label = org.olcbox.app.i18n.S.ping,
                            icon = Icons.Outlined.Bolt
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        customLocations.forEach { location ->
                            LocationSelectorRow(
                                location = location,
                                selectedLocationId = selectedLocationId,
                                pingsState = pingsState,
                                onLocationSelected = onLocationSelected,
                                onLocationSettingsClick = onLocationSettingsClick
                            )
                        }
                    }
                }
            }

            // Bottom "add server" removed — use the top-right "+" (same AddConfigurationSheet).
            if (subscriptionLocations.isEmpty() && customLocations.isEmpty()) {
                FilledTonalButton(
                    onClick = onAddSubscriptionClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = org.olcbox.app.i18n.S.addConnection,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun RelaySetupCard(
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit
) {
    val language by org.olcbox.app.i18n.AppLocale.language.collectAsState()
    @Suppress("UNUSED_EXPRESSION")
    language
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = org.olcbox.app.i18n.S.addRelaySetup,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp)
        )

        SetupActionRow(
            title = org.olcbox.app.i18n.S.addSubscription,
            subtitle = org.olcbox.app.i18n.S.addSubscriptionHint,
            icon = Icons.Outlined.QrCodeScanner,
            prominent = true,
            onClick = onAddSubscriptionClick
        )

        SetupActionRow(
            title = org.olcbox.app.i18n.S.createCustomLocation,
            subtitle = org.olcbox.app.i18n.S.createCustomLocationHint,
            icon = Icons.Outlined.Add,
            onClick = onAddLocationClick
        )
    }
}

@Composable
private fun SetupActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    prominent: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val borderColor = if (prominent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    val contentColor = if (prominent) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = if (prominent) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (prominent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LocationGroupHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 2.dp, start = 4.dp)
    )
}

@Composable
private fun SubscriptionInfoPanel(
    subscription: SubscriptionMetadata?,
    uriHandler: UriHandler,
) {
    if (subscription == null) return

    fun usableExpire(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (v.equals("regular", true) || v.equals("bypass", true)) return null
        return v.takeIf {
            it == "\u221e" || it == "∞" || it.contains('.') ||
                it.contains('-') || it.any { ch -> ch.isDigit() }
        }
    }
    val announce = subscription.displayAnnounce()
        ?: subscription.displayDescription()?.takeUnless { usableExpire(it) != null }
    val expire = usableExpire(subscription.description) ?: usableExpire(subscription.comment)
    val trafficQuota = parseTrafficQuota(subscription.used, subscription.available)
    val unlimitedTraffic = when {
        trafficQuota != null -> null
        !subscription.used.isNullOrBlank() && !subscription.available.isNullOrBlank() ->
            org.olcbox.app.i18n.S.trafficSummary(subscription.used!!, subscription.available!!)
        !subscription.used.isNullOrBlank() ->
            org.olcbox.app.i18n.S.localizeDataUnit(subscription.used!!)
        else -> null
    }
    val supportUrl = subscription.supportUrl
    val webPageUrl = subscription.webPageUrl
    val hasAnything = !announce.isNullOrBlank() ||
        !expire.isNullOrBlank() ||
        trafficQuota != null ||
        !unlimitedTraffic.isNullOrBlank() ||
        !supportUrl.isNullOrBlank() ||
        !webPageUrl.isNullOrBlank()
    if (!hasAnything) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!announce.isNullOrBlank()) {
            SubscriptionInfoFrame(label = org.olcbox.app.i18n.S.description) {
                Text(
                    text = announce,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!expire.isNullOrBlank() || trafficQuota != null || !unlimitedTraffic.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!expire.isNullOrBlank()) {
                    SubscriptionInfoFrame(
                        label = org.olcbox.app.i18n.S.subscriptionExpires,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = expire,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (trafficQuota != null || !unlimitedTraffic.isNullOrBlank()) {
                    SubscriptionInfoFrame(
                        label = org.olcbox.app.i18n.S.traffic,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (trafficQuota != null) {
                            TrafficQuotaIndicator(
                                used = subscription.used,
                                available = subscription.available,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else if (!unlimitedTraffic.isNullOrBlank()) {
                            Text(
                                text = unlimitedTraffic,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (!supportUrl.isNullOrBlank() || !webPageUrl.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!supportUrl.isNullOrBlank()) {
                    FilledTonalButton(
                        onClick = { runCatching { uriHandler.openUri(supportUrl) } },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = org.olcbox.app.i18n.S.support,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!webPageUrl.isNullOrBlank()) {
                    FilledTonalButton(
                        onClick = { runCatching { uriHandler.openUri(webPageUrl) } },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = org.olcbox.app.i18n.S.website,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun SubscriptionInfoFrame(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
    val border = BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
    if (onClick != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            border = border,
            onClick = onClick,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            border = border,
            content = body,
        )
    }
}

@Composable
private fun SubscriptionGroupHeader(
    locations: List<LocationItem>,
    modifier: Modifier = Modifier
) {
    val first = locations.firstOrNull()
    val title = first?.subscriptionTitle().orEmpty()

    Column(modifier = modifier.padding(start = 4.dp, top = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LocationSelectorRow(
    location: LocationItem,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit
) {
    val pingMs = pingsState.pingFor(location.storageId)
    val isLoading = pingsState.isChecking(location.storageId)
    val isOffline = pingsState.isOffline(location.storageId)

    LocationRow(
        location = location,
        isSelected = selectedLocationId == location.storageId,
        isLoading = isLoading,
        isError = isOffline,
        pingMs = pingMs,
        settingsEnabled = location.config?.isMihomo() != true,
        onSettingsClick = {
            onLocationSettingsClick(location.storageId)
        },
        onClick = {
            onLocationSelected(location.storageId)
        }
    )
}

private fun PingsState.pingFor(locationId: String): Int? {
    return when (this) {
        PingsState.Idle -> null

        is PingsState.Loading -> {
            if (currentPings.containsKey(locationId)) {
                currentPings[locationId]
            } else {
                lastPings?.get(locationId)
            }
        }

        is PingsState.Success -> {
            pings[locationId]
        }

        is PingsState.Error -> {
            lastPings?.get(locationId)
        }
    }
}

private fun PingsState.isChecking(locationId: String): Boolean {
    return this is PingsState.Loading && locationId in pendingLocationIds
}

private fun PingsState.isOffline(locationId: String): Boolean {
    return when (this) {
        PingsState.Idle -> false

        is PingsState.Loading -> {
            currentPings.containsKey(locationId) && currentPings[locationId] == null
        }

        is PingsState.Success -> {
            pings.containsKey(locationId) && pings[locationId] == null
        }

        is PingsState.Error -> false
    }
}

private fun LocationItem.subscriptionGroupKey(): String {
    return listOfNotNull(
        metadata?.subscription?.name?.takeIf { it.isNotBlank() },
        subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString("|").ifBlank { storageId }
}

private fun LocationItem.subscriptionTitle(): String {
    val subscription = metadata?.subscription
    val urlToken = subscriptionUrl
        ?.substringAfterLast('/')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val rawName = subscription?.name?.trim()?.takeIf { it.isNotBlank() }
        ?.takeUnless { it == urlToken }
        ?.takeUnless { it.equals("subscriptions", ignoreCase = true) }
        ?.takeUnless { it.equals("regular", ignoreCase = true) }
        ?.takeUnless { it.equals("bypass", ignoreCase = true) }
    val title = (rawName ?: org.olcbox.app.i18n.S.appName)
        .removePrefix("Olc ")
        .removePrefix("olc ")
        .trim()
        .ifBlank { org.olcbox.app.i18n.S.appName }
    return listOfNotNull(
        subscription?.icon?.takeIf { it.isNotBlank() },
        title,
    ).joinToString(" ")
}

private fun plural(value: Long, unit: String): String {
    return "$value $unit${if (value == 1L) "" else "s"}"
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
