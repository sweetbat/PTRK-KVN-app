package org.olcbox.app.ui.features.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.olcbox.app.data.model.parseSubscriptionRefreshIntervalMs
import org.olcbox.app.i18n.S
import org.olcbox.app.ui.features.home.components.AddConfigurationSheet
import org.olcbox.app.ui.features.home.components.HomeScreenAppBar
import org.olcbox.app.ui.features.home.components.LocationSelectorScreen
import org.olcbox.app.ui.features.home.components.LogsSheet
import org.olcbox.app.ui.features.home.components.PtrkBrandHeader
import org.olcbox.app.ui.features.home.components.PtrkConnectButton
import org.olcbox.app.ui.features.home.components.SubscriptionCard
import org.olcbox.app.ui.features.home.components.rememberUptimeText
import org.olcbox.app.ui.features.locations.LocationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    scrollState: ScrollState,
    onToggleClick: () -> Unit = { viewModel.ToggleVpn() },
    onImportFileRequested: () -> Unit = {},
    onImportFromClipboardRequested: (onImported: () -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    onScanQrRequested: () -> Unit = {},
    onCopyConfigRequested: () -> Unit = { viewModel.onCopyFullConfigClicked() },
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    showAppSettingsButton: Boolean = false,
    canScanQr: Boolean = false,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onOpenLocationSettings: (String?) -> Unit,
    onAddLocation: () -> Unit
) {
    var isLogsSheetOpen by remember { mutableStateOf(false) }
    var isAddSheetOpen by remember { mutableStateOf(false) }
    var isManualImportOpen by remember { mutableStateOf(false) }
    var manualImportText by remember { mutableStateOf("") }
    var manualImportError by remember { mutableStateOf<String?>(null) }
    var manualImportBusy by remember { mutableStateOf(false) }
    var manualSubscriptionRefresh by remember { mutableStateOf("") }
    var manualSubscriptionAllowInsecure by remember { mutableStateOf(false) }
    var updatingSubscriptionUrl by remember { mutableStateOf<String?>(null) }

    val state by viewModel.state.collectAsState()
    val language by org.olcbox.app.i18n.AppLocale.language.collectAsState()
    @Suppress("UNUSED_EXPRESSION")
    language
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pingsState = locationViewModel.pingsState
    val locations = locationViewModel.locations.toList()

    val requiresSetup = !state.canStartVpn && !state.isVpnConnected && !state.isVpnLoading

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onForeground()
    }

    fun refreshHttpPings(targetLocationIds: List<String>? = null) {
        locationViewModel.refreshPings(
            targetLocationIds = targetLocationIds,
            performPing = { config ->
                viewModel.performPingFor(config)
            },
        )
    }

    fun updateSubscriptions(urls: List<String>) {
        if (updatingSubscriptionUrl != null || urls.isEmpty()) return
        updatingSubscriptionUrl = urls.first()
        var index = 0
        var totalUpdated = 0
        fun next() {
            if (index >= urls.size) {
                locationViewModel.loadLocations {
                    viewModel.restartVpnIfRunning()
                    updatingSubscriptionUrl = null
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (totalUpdated > 0) {
                                S.subscriptionUpdated
                            } else {
                                S.subscriptionsUpToDate
                            }
                        )
                    }
                }
                return
            }
            val url = urls[index++]
            updatingSubscriptionUrl = url
            viewModel.refreshSubscription(
                subscriptionUrl = url,
                onComplete = { updatedCount ->
                    totalUpdated += updatedCount
                    next()
                },
                onError = { message ->
                    updatingSubscriptionUrl = null
                    scope.launch {
                        snackbarHostState.showSnackbar(S.couldNotUpdateSubscription(message))
                    }
                }
            )
        }
        next()
    }

    fun updateSubscription(subscriptionUrl: String) {
        updateSubscriptions(listOf(subscriptionUrl))
    }

    fun updateAllSubscriptions() {
        val urls = locations
            .mapNotNull { it.subscriptionUrl?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
        if (urls.isEmpty()) return
        updateSubscriptions(urls)
    }

    Scaffold(
        snackbarHost = {
            // Keep errors visible above the IME / nav bar (import dialog + keyboard).
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp),
            )
        },
        topBar = {
            HomeScreenAppBar(
                onHistoryClick = { isLogsSheetOpen = true },
                showAppSettingsButton = showAppSettingsButton,
                onAppSettingsClick = onAppSettingsClick,
                showSplitTunnelingButton = showSplitTunnelingButton,
                onSplitTunnelingClick = onSplitTunnelingClick,
                onAddClick = { isAddSheetOpen = true }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PtrkBrandHeader()

            Spacer(modifier = Modifier.height(16.dp))

            SubscriptionCard(
                location = state.selectedLocation,
                onRefreshClick = state.selectedLocation?.subscriptionUrl?.let {
                    { updateAllSubscriptions() }
                },
            )

            Spacer(modifier = Modifier.height(20.dp))

            val uptime = rememberUptimeText(state.connectedSinceEpochMs)
            PtrkConnectButton(
                isActive = state.isVpnConnected,
                isLoading = state.isVpnLoading,
                requiresSetup = requiresSetup,
                uptimeText = uptime,
                onClick = {
                    if (requiresSetup) {
                        isAddSheetOpen = true
                    } else {
                        onToggleClick()
                    }
                },
            )

            Spacer(modifier = Modifier.height(20.dp))

            LocationSelectorScreen(
                onRefreshClick = { targetIds ->
                    refreshHttpPings(targetIds)
                },
                onSubscriptionUpdateClick = ::updateSubscription,
                updatingSubscriptionUrl = updatingSubscriptionUrl,
                onAddSubscriptionClick = {
                    isAddSheetOpen = true
                },
                locations = locations,
                selectedLocationId = locationViewModel.selectedLocationId,
                pingsState = pingsState,
                onLocationSelected = { id ->
                    locationViewModel.selectLocation(id) {
                        viewModel.loadCurrentConfig()
                        viewModel.restartVpnIfRunning()
                    }
                },
                onLocationSettingsClick = { id ->
                    onOpenLocationSettings(id)
                },
                onAddLocationClick = {
                    // Same menu as the top-right "+" — not a separate create-only flow.
                    isAddSheetOpen = true
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (isLogsSheetOpen) {
            val logs by viewModel.logs.collectAsState()

            LogsSheet(
                logs = logs,
                onSaveClick = {
                    onSaveLogsRequested(
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onShareClick = {
                    viewModel.onShareLogs(
                        onShared = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        onError = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onDismiss = {
                    isLogsSheetOpen = false
                }
            )
        }

        if (isAddSheetOpen) {
            AddConfigurationSheet(
                canScanQr = canScanQr,
                onDismiss = {
                    isAddSheetOpen = false
                },
                onScanQrClick = {
                    isAddSheetOpen = false
                    onScanQrRequested()
                },
                onPasteLinkClick = {
                    isAddSheetOpen = false
                    isManualImportOpen = true
                },
                onImportFileClick = {
                    isAddSheetOpen = false
                    onImportFileRequested()
                },
                onAddCustomLocationClick = {
                    isAddSheetOpen = false
                    onAddLocation()
                }
            )
        }

        if (isManualImportOpen) {
            val normalizedImportText = manualImportText.trim()
            val isSubscriptionUrl = normalizedImportText.startsWith("https://", ignoreCase = true) ||
                normalizedImportText.startsWith("http://", ignoreCase = true)
            val subscriptionRefreshIntervalMs = manualSubscriptionRefresh
                .takeIf { it.isNotBlank() }
                ?.let(::parseSubscriptionRefreshIntervalMs)
            val subscriptionRefreshError = isSubscriptionUrl &&
                manualSubscriptionRefresh.isNotBlank() &&
                subscriptionRefreshIntervalMs == null

            AlertDialog(
                onDismissRequest = {
                    if (manualImportBusy) return@AlertDialog
                    isManualImportOpen = false
                    manualImportError = null
                    manualSubscriptionRefresh = ""
                    manualSubscriptionAllowInsecure = false
                },
                title = { Text(S.importLinkTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = manualImportText,
                            onValueChange = {
                                manualImportText = it
                                manualImportError = null
                            },
                            label = { Text(S.importLinkLabel) },
                            placeholder = { Text(S.importLinkPlaceholder) },
                            minLines = 3,
                            maxLines = 6,
                            isError = manualImportError != null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (isSubscriptionUrl) {
                            OutlinedTextField(
                                value = manualSubscriptionRefresh,
                                onValueChange = { value ->
                                    manualSubscriptionRefresh = value
                                        .lowercase()
                                        .filter { it.isDigit() || it in "smhd" }
                                        .take(8)
                                },
                                label = { Text(S.subscriptionRefreshRate) },
                                placeholder = { Text(S.auto) },
                                supportingText = {
                                    Text(
                                        if (subscriptionRefreshError) {
                                            S.subscriptionRefreshError
                                        } else {
                                            S.subscriptionRefreshOptional
                                        }
                                    )
                                },
                                isError = subscriptionRefreshError,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = manualSubscriptionAllowInsecure,
                                    onCheckedChange = { manualSubscriptionAllowInsecure = it }
                                )
                                Text(S.allowInsecureRequests)
                            }
                        }
                        if (!manualImportError.isNullOrBlank()) {
                            Text(
                                text = manualImportError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = manualImportText.isNotBlank() &&
                            !subscriptionRefreshError &&
                            !manualImportBusy,
                        onClick = {
                            manualImportBusy = true
                            manualImportError = null
                            viewModel.onImportFullConfig(
                                rawText = manualImportText,
                                subscriptionRefreshIntervalMs = subscriptionRefreshIntervalMs,
                                allowInsecureSubscriptionRequests = manualSubscriptionAllowInsecure,
                                onComplete = {
                                    manualImportBusy = false
                                    isManualImportOpen = false
                                    manualImportText = ""
                                    manualImportError = null
                                    manualSubscriptionRefresh = ""
                                    manualSubscriptionAllowInsecure = false
                                    locationViewModel.loadLocations {
                                        viewModel.loadCurrentConfig()
                                    }
                                    scope.launch {
                                        snackbarHostState.showSnackbar(S.configurationImported)
                                    }
                                },
                                onError = { message ->
                                    manualImportBusy = false
                                    // Keep dialog open and show the error inside it (above IME /
                                    // not buried under the modal).
                                    manualImportError = message
                                }
                            )
                        }
                    ) {
                        Text(S.importAction)
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !manualImportBusy,
                        onClick = {
                            viewModel.readImportTextFromClipboard(
                                onText = { text ->
                                    manualImportText = text
                                    manualImportError = null
                                },
                                { message ->
                                    manualImportError = message
                                }
                            )
                        }
                    ) {
                        Text(S.pasteFromClipboard)
                    }
                }
            )
        }
    }
}
