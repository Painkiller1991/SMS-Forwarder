package com.smsforwarder.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.data.EndpointDeliveryStatus
import com.smsforwarder.app.data.ReceiverEndpoint
import com.smsforwarder.app.data.ThemeMode
import com.smsforwarder.app.net.ReceiveServer
import com.smsforwarder.app.tile.QsTileHelper
import com.smsforwarder.app.ui.UiState
import com.smsforwarder.app.ui.components.AppShell
import com.smsforwarder.app.ui.components.HotspotGuideCard
import com.smsforwarder.app.ui.components.SectionHeader
import com.smsforwarder.app.ui.components.StatusChip
import com.smsforwarder.app.ui.components.TonalCard
import com.smsforwarder.app.ui.components.VersionLabel
import java.text.DateFormat
import java.util.Date

@Composable
fun SenderScreen(
    state: UiState,
    hasSmsPermission: Boolean,
    needsBatteryUnrestricted: Boolean,
    onEnableChange: (Boolean) -> Unit,
    onScanQr: () -> Unit,
    onSaveEndpoint: (host: String, port: String, secret: String, extraSecret: String) -> Unit,
    onRemoveEndpoint: (String) -> Unit,
    onToggleEndpoint: (id: String, enabled: Boolean) -> Unit,
    onSetEndpointExtraSecret: (id: String, extra: String, confirm: String) -> String?,
    onSetDefaultExtraSecret: (extra: String, confirm: String) -> String?,
    onRequestSmsPermission: () -> Unit,
    onTestSend: () -> Unit,
    onBatteryUnrestricted: () -> Unit,
    onSnoozeBatteryBanner: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onSendDebugPing: () -> Unit,
    onCopyDebugLog: () -> Unit,
    onClearDebugLog: () -> Unit,
    readDebugLog: () -> String,
    onChangeRole: () -> Unit,
    onDrainOutbox: () -> Unit,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    onOpenSystemHotspot: () -> Unit,
    onCopyHotspotCredentials: (String) -> Unit
) {
    val context = LocalContext.current
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(ReceiveServer.DEFAULT_PORT.toString()) }
    var secret by remember { mutableStateOf("") }
    var extraSecret by remember { mutableStateOf(state.defaultExtraSecret) }
    var extraConfirm by remember { mutableStateOf(state.defaultExtraSecret) }
    var showPendingSync by remember { mutableStateOf(false) }
    var showOemGuide by remember { mutableStateOf(false) }
    var showTileHelp by remember { mutableStateOf(false) }
    var editingExtraId by remember { mutableStateOf<String?>(null) }
    var defaultExtraDraft by remember { mutableStateOf(state.defaultExtraSecret) }
    var defaultExtraConfirm by remember { mutableStateOf(state.defaultExtraSecret) }
    var formError by remember { mutableStateOf<String?>(null) }
    // Collapse setup sections when at least one receiver exists.
    var defaultExtraExpanded by remember { mutableStateOf(state.endpoints.isEmpty()) }
    var addDeviceExpanded by remember { mutableStateOf(state.endpoints.isEmpty()) }
    val pending = state.pendingSyncCount
    val showBattery = needsBatteryUnrestricted && !state.batteryBannerSnoozed

    if (showPendingSync) {
        AlertDialog(
            onDismissRequest = { showPendingSync = false },
            title = { Text("Pending sync") },
            text = {
                Text(
                    when {
                        pending == 0 -> "No messages waiting to send."
                        pending == 1 -> "1 message waiting to send to your receivers."
                        else -> "$pending messages waiting to send to your receivers."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDrainOutbox()
                        showPendingSync = false
                    },
                    enabled = pending > 0
                ) {
                    Text("Sync now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPendingSync = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showOemGuide) {
        OemBatteryGuideDialog(
            onDismiss = { showOemGuide = false },
            onOpenBatterySettings = {
                showOemGuide = false
                onBatteryUnrestricted()
            }
        )
    }

    if (showTileHelp) {
        AlertDialog(
            onDismissRequest = { showTileHelp = false },
            title = { Text("Home widget & Quick Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Home widget (what you find under Widgets):\n" +
                            "1. Long-press an empty spot on the home screen\n" +
                            "2. Tap Widgets\n" +
                            "3. Find “SMS Forwarder” / “SMS WiFi Forwarder”\n" +
                            "4. Drag it onto the home screen",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Sender: shows pending sync; tap to Sync.\n" +
                            "Receiver: tap to turn Receive ON/OFF.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Optional shade tile:\n${QsTileHelper.MANUAL_STEPS}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        QsTileHelper.showAddToast(context)
                        showTileHelp = false
                    }
                ) {
                    Text("Add shade tile")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTileHelp = false }) { Text("Close") }
            }
        )
    }

    editingExtraId?.let { id ->
        val ep = state.endpoints.firstOrNull { it.id == id }
        if (ep != null) {
            var draft by remember(id) { mutableStateOf(ep.extraSecret) }
            var confirm by remember(id) { mutableStateOf(ep.extraSecret) }
            var err by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { editingExtraId = null },
                title = { Text("Extra Secret — ${ep.displayName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Must match the receiver’s Extra Secret. Leave blank only for legacy peers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text("Extra Secret") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirm,
                            onValueChange = { confirm = it },
                            label = { Text("Confirm") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        err?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val e = onSetEndpointExtraSecret(id, draft, confirm)
                        if (e != null) err = e else editingExtraId = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editingExtraId = null }) { Text("Cancel") }
                }
            )
        }
    }

    AppShell(
        title = "Send mode",
        themeMode = state.themeMode,
        onThemeMode = onThemeMode,
        onDebugPing = onSendDebugPing,
        onCopyDebugLog = onCopyDebugLog,
        onClearDebugLog = onClearDebugLog,
        readDebugLog = readDebugLog,
        onChangeRole = onChangeRole,
        pendingSyncCount = pending,
        onPendingSync = { showPendingSync = true }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            VersionLabel()

            if (!hasSmsPermission) {
                TonalCard {
                    Text(
                        "SMS permission required",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestSmsPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant SMS permission")
                    }
                }
            }

            if (showBattery) {
                TonalCard {
                    Text("Background battery", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Allow unrestricted battery so forwarding still works when the app is closed. " +
                            "Path: Apps → SMS Forwarder → Battery → Unrestricted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onBatteryUnrestricted,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.BatterySaver,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Allow unrestricted battery", modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onSnoozeBatteryBanner) {
                            Text("Later (7 days)")
                        }
                        TextButton(onClick = { showOemGuide = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("OEM help")
                        }
                    }
                }
            }

            HotspotGuideCard(
                isSender = true,
                hotspotActive = state.hotspotActive,
                hotspotSsid = state.hotspotSsid,
                hotspotPassword = state.hotspotPassword,
                hotspotError = state.hotspotError,
                onStartHotspot = onStartHotspot,
                onStopHotspot = onStopHotspot,
                onOpenSystemHotspot = onOpenSystemHotspot,
                onCopyCredentials = onCopyHotspotCredentials
            )

            TonalCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Connection status", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                StatusChip(
                    label = when {
                        state.pairCheckInProgress -> "Checking…"
                        !state.paired -> "No devices"
                        state.reachable -> "Reachable ${state.reachableCount}/${state.endpointCount}"
                        else -> "Unreachable 0/${state.endpointCount}"
                    },
                    active = state.reachable
                )
                if (pending > 0) {
                    Spacer(Modifier.height(8.dp))
                    StatusChip(
                        label = if (pending == 1) "1 pending sync" else "$pending pending sync",
                        active = true
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "SMS is sent to every enabled device on the list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Forwarding enabled", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (!state.paired) {
                                "Add at least one receiver first"
                            } else {
                                "Sending to ${state.endpointCount} device(s)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.senderEnabled && state.paired && hasSmsPermission,
                        onCheckedChange = onEnableChange,
                        enabled = state.paired && hasSmsPermission
                    )
                }
            }

            TonalCard {
                SectionHeader(
                    "Receivers",
                    "Up to ${ReceiverEndpoint.MAX_DEVICES} devices (phone, PC, …)"
                )
                Spacer(Modifier.height(12.dp))
                if (state.endpoints.isEmpty()) {
                    Text(
                        "No devices yet — expand Add device below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.endpoints.forEachIndexed { index, ep ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                        }
                        EndpointRow(
                            endpoint = ep,
                            status = state.endpointStatus[ep.id],
                            onToggle = { onToggleEndpoint(ep.id, it) },
                            onRemove = { onRemoveEndpoint(ep.id) },
                            onEditExtra = { editingExtraId = ep.id }
                        )
                    }
                }
            }

            CollapsibleCard(
                title = "Default Extra Secret",
                subtitle = if (state.defaultExtraSecret.isNotEmpty()) {
                    "Saved · applied to new pairings"
                } else {
                    "Optional · applied when pairing new devices"
                },
                expanded = defaultExtraExpanded,
                onToggle = { defaultExtraExpanded = !defaultExtraExpanded }
            ) {
                Text(
                    "Not stored in the QR. Leave blank only for legacy peers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = defaultExtraDraft,
                    onValueChange = { defaultExtraDraft = it },
                    label = { Text("Extra Secret (≥${ReceiverEndpoint.MIN_EXTRA_SECRET_LEN})") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = defaultExtraConfirm,
                    onValueChange = { defaultExtraConfirm = it },
                    label = { Text("Confirm Extra Secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        formError = onSetDefaultExtraSecret(defaultExtraDraft, defaultExtraConfirm)
                        if (formError == null) {
                            extraSecret = defaultExtraDraft
                            extraConfirm = defaultExtraConfirm
                            defaultExtraExpanded = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save default Extra Secret")
                }
                formError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            CollapsibleCard(
                title = "Add device",
                subtitle = "Scan QR or enter IP / port / secret",
                expanded = addDeviceExpanded,
                onToggle = { addDeviceExpanded = !addDeviceExpanded }
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP address") },
                    placeholder = { Text("192.168.1.20") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Shared secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = extraSecret,
                    onValueChange = { extraSecret = it },
                    label = { Text("Extra Secret (required for new pairs)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = extraConfirm,
                    onValueChange = { extraConfirm = it },
                    label = { Text("Confirm Extra Secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (extraSecret.isNotEmpty() && extraSecret != extraConfirm) {
                                formError = "Extra Secret confirmation does not match"
                                return@Button
                            }
                            formError = null
                            onSaveEndpoint(host, port, secret, extraSecret)
                            host = ""
                            secret = ""
                            port = ReceiveServer.DEFAULT_PORT.toString()
                            addDeviceExpanded = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onScanQr, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR")
                    }
                }
            }

            TonalCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Widgets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Home widget & Quick Settings", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Home screen: long-press → Widgets → SMS Forwarder. Also available as a shade tile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = { QsTileHelper.showAddToast(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Quick Settings tile")
                }
                TextButton(onClick = { showTileHelp = true }) {
                    Text("How to add home widget / tile")
                }
            }

            Button(
                onClick = onTestSend,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = state.paired,
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Test send (all enabled)")
            }
            Text(
                "If Test fails with timeout, the router may block phone‑to‑phone (AP isolation).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    TonalCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand"
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun EndpointRow(
    endpoint: ReceiverEndpoint,
    status: EndpointDeliveryStatus?,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onEditExtra: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    endpoint.displayName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${endpoint.host}:${endpoint.port}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = endpoint.enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val reach = when (status?.reachable) {
                true -> "Reachable"
                false -> "Unreachable"
                null -> "—"
            }
            val extra = if (endpoint.extraSecret.isNotEmpty()) "Extra Secret set" else "Legacy"
            Text(
                "$reach · $extra",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when (status?.reachable) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            IconButton(onClick = onEditExtra, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Lock, contentDescription = "Extra Secret")
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        status?.lastError?.takeIf { it.isNotBlank() }?.let { err ->
            Text(
                "Last error: ${err.take(80)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        status?.lastSyncedAt?.let { ts ->
            val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(ts))
            Text(
                "Last sync: $formatted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
