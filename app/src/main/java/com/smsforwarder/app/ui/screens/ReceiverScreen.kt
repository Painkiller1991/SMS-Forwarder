package com.smsforwarder.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.tile.QsTileHelper
import com.smsforwarder.app.data.DayBoundary
import com.smsforwarder.app.data.MessageEntity
import com.smsforwarder.app.data.ReceiverEndpoint
import com.smsforwarder.app.data.ThemeMode
import com.smsforwarder.app.net.ReceiveServer
import com.smsforwarder.app.otp.OtpParser
import com.smsforwarder.app.pairing.QrBitmap
import com.smsforwarder.app.ui.UiState
import com.smsforwarder.app.ui.components.AddressHero
import com.smsforwarder.app.ui.components.AppShell
import com.smsforwarder.app.ui.components.HotspotGuideCard
import com.smsforwarder.app.ui.components.SectionHeader
import com.smsforwarder.app.ui.components.StatusChip
import com.smsforwarder.app.ui.components.SwipeMessageCard
import com.smsforwarder.app.ui.components.TonalCard
import com.smsforwarder.app.ui.components.VersionLabel

private enum class InboxFilter { ALL, OTP }

@Composable
fun ReceiverScreen(
    state: UiState,
    messages: List<MessageEntity>,
    hasHistory: Boolean,
    hasNotificationPermission: Boolean,
    needsBatteryUnrestricted: Boolean,
    needsAutofillSetup: Boolean,
    onToggleReceive: (Boolean) -> Unit,
    onOpenAutofill: () -> Unit,
    onClearInbox: () -> Unit,
    onOpenHistory: () -> Unit,
    onCopyOtp: (String) -> Unit,
    onCopyBody: (String) -> Unit,
    onTogglePin: (MessageEntity) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onTestSend: () -> Unit,
    onBatteryUnrestricted: () -> Unit,
    onSnoozeBatteryBanner: () -> Unit,
    onSetExtraSecret: (extra: String, confirm: String) -> String?,
    onCopyAddress: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onSendDebugPing: () -> Unit,
    onCopyDebugLog: () -> Unit,
    onClearDebugLog: () -> Unit,
    readDebugLog: () -> String,
    onChangeRole: () -> Unit
) {
    val qrBitmap: Bitmap? = remember(state.pairingPayload) {
        state.pairingPayload.takeIf { it.isNotBlank() }?.let { QrBitmap.encode(it) }
    }
    var expandedMessageId by remember { mutableStateOf<Long?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var inboxFilter by remember { mutableStateOf(InboxFilter.ALL) }
    var showExtraDialog by remember { mutableStateOf(false) }
    var showOemGuide by remember { mutableStateOf(false) }
    var showTileHelp by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) expandedMessageId = null
    }

    val dayStart = DayBoundary.startOfTodayMillis()
    val filtered = remember(messages, searchQuery, inboxFilter) {
        messages.filter { msg ->
            val q = searchQuery.trim()
            val matchesSearch = q.isEmpty() ||
                msg.sender.contains(q, ignoreCase = true) ||
                msg.body.contains(q, ignoreCase = true) ||
                (msg.otp?.contains(q, ignoreCase = true) == true)
            val hasOtp = !msg.otp.isNullOrBlank() || OtpParser.extract(msg.body) != null
            val matchesFilter = when (inboxFilter) {
                InboxFilter.ALL -> true
                InboxFilter.OTP -> hasOtp
            }
            matchesSearch && matchesFilter
        }
    }
    val pinned = remember(filtered) { filtered.filter { it.pinned } }
    val today = remember(filtered, dayStart) {
        filtered.filter { !it.pinned && it.receivedAt >= dayStart }
    }
    val showBattery = needsBatteryUnrestricted && !state.batteryBannerSnoozed

    if (showExtraDialog) {
        var draft by remember { mutableStateOf(state.extraSecret) }
        var confirm by remember { mutableStateOf(state.extraSecret) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showExtraDialog = false },
            title = { Text("Extra Secret") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Passphrase both phones must know. Not included in the QR. " +
                            "New pairings: at least ${ReceiverEndpoint.MIN_EXTRA_SECRET_LEN} characters. " +
                            "Leave blank only to keep working with legacy peers.",
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
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val e = onSetExtraSecret(draft, confirm)
                    if (e != null) err = e else showExtraDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showExtraDialog = false }) { Text("Cancel") }
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
                        "Home widget:\n" +
                            "Long-press home screen → Widgets → SMS Forwarder → drag to home.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Tap the widget to turn Receive ON/OFF.",
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

    AppShell(
        title = "Receive mode",
        themeMode = state.themeMode,
        onThemeMode = onThemeMode,
        onDebugPing = onSendDebugPing,
        onCopyDebugLog = onCopyDebugLog,
        onClearDebugLog = onClearDebugLog,
        readDebugLog = readDebugLog,
        onChangeRole = onChangeRole,
        onOpenHistory = onOpenHistory
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                VersionLabel()
                Spacer(Modifier.height(4.dp))
                StatusChip(
                    label = if (state.receiveEnabled) "Listening ON" else "Port closed",
                    active = state.receiveEnabled
                )
                Spacer(Modifier.height(12.dp))
                AddressHero(
                    ip = state.localIp,
                    port = ReceiveServer.DEFAULT_PORT,
                    onCopy = onCopyAddress,
                    copyEnabled = state.localIp.isNotBlank()
                )
                if (state.sharedSecret.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Secret: ${state.sharedSecret.take(8)}…",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.extraSecret.isNotEmpty()) {
                        "Extra Secret: set (${state.extraSecret.length} chars)"
                    } else {
                        "Extra Secret: not set (legacy QR-only)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                HotspotGuideCard(
                    isSender = false,
                    onHotspotLan = state.onHotspotLan
                )
            }

            item {
                TonalCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Listening port", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.receiveEnabled) {
                                    "Receiving on port ${ReceiveServer.DEFAULT_PORT}"
                                } else {
                                    "OFF — sender cannot reach this phone"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = state.receiveEnabled, onCheckedChange = onToggleReceive)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Keep this port open in your firewall if needed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.vpnActive) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (state.localIp.isNotBlank()) {
                                "VPN on — listening on Wi‑Fi ${state.localIp}. If the sender still cannot reach you, exclude this app from the VPN or turn off VPN lockdown."
                            } else {
                                "VPN on — waiting for a Wi‑Fi address. If receive fails, exclude this app from the VPN or turn off VPN lockdown."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                if (!hasNotificationPermission) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Notification permission recommended",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (showBattery) {
                item {
                    TonalCard {
                        Text("Background battery", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Apps → SMS Forwarder → Battery → Unrestricted. Then check OEM keep-alive settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = onBatteryUnrestricted,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.BatterySaver, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Allow unrestricted battery", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                        Row {
                            TextButton(onClick = onSnoozeBatteryBanner) {
                                Text("Later (7 days)")
                            }
                            TextButton(onClick = { showOemGuide = true }) {
                                Icon(Icons.AutoMirrored.Filled.HelpOutline, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("OEM help")
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader("Actions", "Test your connection")
                Spacer(Modifier.height(8.dp))
                TonalCard {
                    ActionRow(
                        icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        title = "Test send",
                        enabled = state.receiveEnabled,
                        onClick = onTestSend
                    )
                    Spacer(Modifier.height(4.dp))
                    ActionRow(
                        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        title = if (state.extraSecret.isNotEmpty()) "Edit Extra Secret" else "Set Extra Secret",
                        onClick = { showExtraDialog = true }
                    )
                    Spacer(Modifier.height(4.dp))
                    ActionRow(
                        icon = { Icon(Icons.Default.Widgets, contentDescription = null) },
                        title = "Add home widget (Widgets list)",
                        onClick = { showTileHelp = true }
                    )
                    Spacer(Modifier.height(4.dp))
                    ActionRow(
                        icon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                        title = "Add Quick Settings shade tile",
                        onClick = { QsTileHelper.showAddToast(context) }
                    )
                    if (!showBattery && needsBatteryUnrestricted) {
                        Spacer(Modifier.height(4.dp))
                        ActionRow(
                            icon = { Icon(Icons.Default.BatterySaver, contentDescription = null) },
                            title = "Battery / OEM keep-alive",
                            onClick = { showOemGuide = true }
                        )
                    }
                    if (needsAutofillSetup) {
                        Spacer(Modifier.height(4.dp))
                        ActionRow(
                            icon = { Icon(Icons.Default.Password, contentDescription = null) },
                            title = "Open Autofill settings",
                            onClick = onOpenAutofill
                        )
                    }
                }
            }

            item {
                SectionHeader("QR pairing", "Scan to pair a sender device")
                Spacer(Modifier.height(8.dp))
                TonalCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pairing QR", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (qrBitmap != null) "Show this to the sender phone (Extra Secret is not in the QR)"
                                else "Connect to Wi‑Fi to show QR",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (qrBitmap != null) {
                        Spacer(Modifier.height(12.dp))
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Pairing QR",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Inbox",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        if (today.isNotEmpty()) {
                            Surface(
                                onClick = onClearInbox,
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "Clear all",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "Pinned + today",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search inbox") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = inboxFilter == InboxFilter.ALL,
                            onClick = { inboxFilter = InboxFilter.ALL },
                            label = { Text("All") }
                        )
                        FilterChip(
                            selected = inboxFilter == InboxFilter.OTP,
                            onClick = { inboxFilter = InboxFilter.OTP },
                            label = { Text("OTP") }
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (messages.isEmpty()) "No messages in inbox today."
                        else "No messages match this filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                if (pinned.isNotEmpty()) {
                    item {
                        Text(
                            "Pinned",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(pinned, key = { "p-${it.id}" }) { msg ->
                        InboxSwipeItem(
                            msg = msg,
                            expandedMessageId = expandedMessageId,
                            onExpandedChange = { open ->
                                expandedMessageId = if (open) msg.id else null
                            },
                            onCopyOtp = onCopyOtp,
                            onCopyBody = onCopyBody,
                            onTogglePin = onTogglePin,
                            onDeleteMessage = onDeleteMessage
                        )
                    }
                }
                if (today.isNotEmpty()) {
                    item {
                        Text(
                            "Today",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(today, key = { "t-${it.id}" }) { msg ->
                        InboxSwipeItem(
                            msg = msg,
                            expandedMessageId = expandedMessageId,
                            onExpandedChange = { open ->
                                expandedMessageId = if (open) msg.id else null
                            },
                            onCopyOtp = onCopyOtp,
                            onCopyBody = onCopyBody,
                            onTogglePin = onTogglePin,
                            onDeleteMessage = onDeleteMessage
                        )
                    }
                }
            }

            if (hasHistory) {
                item {
                    Surface(
                        onClick = onOpenHistory,
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Older messages", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Yesterday & before",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxSwipeItem(
    msg: MessageEntity,
    expandedMessageId: Long?,
    onExpandedChange: (Boolean) -> Unit,
    onCopyOtp: (String) -> Unit,
    onCopyBody: (String) -> Unit,
    onTogglePin: (MessageEntity) -> Unit,
    onDeleteMessage: (Long) -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { it / 4 }
    ) {
        SwipeMessageCard(
            msg = msg,
            expanded = expandedMessageId == msg.id,
            onExpandedChange = onExpandedChange,
            onCopyOtp = onCopyOtp,
            onCopyBody = onCopyBody,
            onTogglePin = {
                onExpandedChange(false)
                onTogglePin(msg)
            },
            onDelete = {
                onExpandedChange(false)
                onDeleteMessage(msg.id)
            }
        )
    }
}

@Composable
private fun ActionRow(
    icon: @Composable () -> Unit,
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(title, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}
