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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.data.DayBoundary
import com.smsforwarder.app.data.MessageEntity
import com.smsforwarder.app.data.ThemeMode
import com.smsforwarder.app.net.ReceiveServer
import com.smsforwarder.app.pairing.QrBitmap
import com.smsforwarder.app.ui.UiState
import com.smsforwarder.app.ui.components.AddressHero
import com.smsforwarder.app.ui.components.AppShell
import com.smsforwarder.app.ui.components.SectionHeader
import com.smsforwarder.app.ui.components.StatusChip
import com.smsforwarder.app.ui.components.SwipeMessageCard
import com.smsforwarder.app.ui.components.TonalCard
import com.smsforwarder.app.ui.components.VersionLabel

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
    onCopyAddress: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onSendDebugPing: () -> Unit,
    onCopyDebugLog: () -> Unit,
    onChangeRole: () -> Unit
) {
    val qrBitmap: Bitmap? = remember(state.pairingPayload) {
        state.pairingPayload.takeIf { it.isNotBlank() }?.let { QrBitmap.encode(it) }
    }
    var expandedMessageId by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) expandedMessageId = null
    }

    val dayStart = DayBoundary.startOfTodayMillis()
    val pinned = remember(messages) { messages.filter { it.pinned } }
    val today = remember(messages, dayStart) {
        messages.filter { !it.pinned && it.receivedAt >= dayStart }
    }

    AppShell(
        title = "Receive mode",
        themeMode = state.themeMode,
        onThemeMode = onThemeMode,
        onDebugPing = onSendDebugPing,
        onCopyDebugLog = onCopyDebugLog,
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
                    if (needsBatteryUnrestricted) {
                        Spacer(Modifier.height(4.dp))
                        ActionRow(
                            icon = { Icon(Icons.Default.BatterySaver, contentDescription = null) },
                            title = "Allow unrestricted battery",
                            onClick = onBatteryUnrestricted
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
                                if (qrBitmap != null) "Show this to the sender phone"
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
                }
            }

            if (messages.isEmpty()) {
                item {
                    Text(
                        "No messages in inbox today.",
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
