package com.smsforwarder.app.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smsforwarder.app.data.MessageEntity
import com.smsforwarder.app.data.ThemeMode
import com.smsforwarder.app.net.ReceiveServer
import com.smsforwarder.app.pairing.QrBitmap
import com.smsforwarder.app.ui.UiState
import com.smsforwarder.app.ui.components.ScreenTopBar
import com.smsforwarder.app.ui.components.VersionLabel
import java.text.DateFormat
import java.util.Date

@Composable
fun ReceiverScreen(
    state: UiState,
    messages: List<MessageEntity>,
    hasNotificationPermission: Boolean,
    needsBatteryUnrestricted: Boolean,
    needsAutofillSetup: Boolean,
    onToggleReceive: (Boolean) -> Unit,
    onOpenAutofill: () -> Unit,
    onClearInbox: () -> Unit,
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

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = "Receive mode",
                themeMode = state.themeMode,
                onThemeMode = onThemeMode,
                onDebugPing = onSendDebugPing,
                onCopyDebugLog = onCopyDebugLog,
                onChangeRole = onChangeRole
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                VersionLabel()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Local Wi‑Fi only. The quiet “Listening …” notification stays on while receive is enabled (low battery; SMS alerts are separate).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                Text("This phone’s address", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.localIp.isNotBlank()) state.localIp else "No Wi‑Fi IP",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "Port  ${ReceiveServer.DEFAULT_PORT}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Type these on the sender, or scan the QR below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCopyAddress,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.localIp.isNotBlank()
                ) {
                    Text("Copy IP + port + secret")
                }
                if (state.sharedSecret.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Secret: ${state.sharedSecret.take(8)}…",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                if (!hasNotificationPermission) {
                    Text("Notification permission recommended", color = MaterialTheme.colorScheme.error)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Listening port", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.receiveEnabled) {
                                "ON — port ${ReceiveServer.DEFAULT_PORT} open (quiet notification)"
                            } else {
                                "OFF — port closed; sender cannot reach this phone"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.receiveEnabled, onCheckedChange = onToggleReceive)
                }
                Text(
                    if (state.receiveEnabled) "Turn OFF anytime to close the port yourself."
                    else "Turn ON when you want to receive SMS on Wi‑Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onTestSend,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.receiveEnabled
                ) {
                    Text("Test send (to this phone)")
                }
                if (needsBatteryUnrestricted) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onBatteryUnrestricted, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow unrestricted battery (needed when closed)")
                    }
                }
            }

            item {
                Text("Pairing QR (optional)", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Pairing QR",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                } else {
                    Text(
                        "Connect to Wi‑Fi to show QR",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (needsAutofillSetup) {
                item {
                    Text("OTP autofill for other apps", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enable this app as Autofill, then focus an OTP field after an SMS arrives.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenAutofill, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Autofill settings")
                    }
                }
            }

            item {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Inbox",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    OutlinedButton(onClick = onClearInbox) { Text("Clear") }
                }
            }

            if (messages.isEmpty()) {
                item {
                    Text(
                        "No forwarded SMS yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    MessageCard(msg)
                }
            }
        }
    }
}

@Composable
private fun MessageCard(msg: MessageEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(msg.sender, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                DateFormat.getDateTimeInstance().format(Date(msg.receivedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(msg.body, color = MaterialTheme.colorScheme.onSurface)
            if (!msg.otp.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "OTP: ${msg.otp}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
