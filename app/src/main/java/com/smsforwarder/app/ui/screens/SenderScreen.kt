package com.smsforwarder.app.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.data.ThemeMode
import com.smsforwarder.app.net.ReceiveServer
import com.smsforwarder.app.ui.UiState
import com.smsforwarder.app.ui.components.AppShell
import com.smsforwarder.app.ui.components.SectionHeader
import com.smsforwarder.app.ui.components.StatusChip
import com.smsforwarder.app.ui.components.TonalCard
import com.smsforwarder.app.ui.components.VersionLabel

@Composable
fun SenderScreen(
    state: UiState,
    hasSmsPermission: Boolean,
    needsBatteryUnrestricted: Boolean,
    onEnableChange: (Boolean) -> Unit,
    onScanQr: () -> Unit,
    onSaveEndpoint: (host: String, port: String, secret: String) -> Unit,
    onRequestSmsPermission: () -> Unit,
    onTestSend: () -> Unit,
    onBatteryUnrestricted: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onSendDebugPing: () -> Unit,
    onCopyDebugLog: () -> Unit,
    onChangeRole: () -> Unit
) {
    var host by remember(state.receiverHost) { mutableStateOf(state.receiverHost) }
    var port by remember(state.receiverPort) {
        mutableStateOf(state.receiverPort.toString().ifBlank { ReceiveServer.DEFAULT_PORT.toString() })
    }
    var secret by remember(state.sharedSecret) { mutableStateOf(state.sharedSecret) }

    AppShell(
        title = "Send mode",
        themeMode = state.themeMode,
        onThemeMode = onThemeMode,
        onDebugPing = onSendDebugPing,
        onCopyDebugLog = onCopyDebugLog,
        onChangeRole = onChangeRole
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
                    Text("SMS permission required", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestSmsPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant SMS permission")
                    }
                }
            }

            if (needsBatteryUnrestricted) {
                TonalCard {
                    Text(
                        "Background battery",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Allow unrestricted battery so forwarding still works when the app is closed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onBatteryUnrestricted,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BatterySaver, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Allow unrestricted battery", modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

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
                        state.paired -> "Paired"
                        state.receiverHost.isNotBlank() -> "Unreachable"
                        else -> "Not paired"
                    },
                    active = state.paired
                )
                if (state.receiverHost.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${state.receiverHost}:${state.receiverPort}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Forwarding enabled", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (!state.paired) "Save IP + port + secret first"
                            else "Sending to ${state.receiverHost}:${state.receiverPort}",
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
                SectionHeader("Endpoint", "Configure the receiving endpoint")
                Spacer(Modifier.height(12.dp))
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
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Shared secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onSaveEndpoint(host, port, secret) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onScanQr, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR")
                    }
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
                Text("Test send")
            }
            Text(
                "If Test fails with timeout, the router may block phone‑to‑phone (AP isolation).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
