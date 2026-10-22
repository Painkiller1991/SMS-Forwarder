package com.smsforwarder.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.data.ThemeMode
import com.smsforwarder.app.net.ReceiveServer
import com.smsforwarder.app.ui.UiState
import com.smsforwarder.app.ui.components.ScreenTopBar
import com.smsforwarder.app.ui.components.VersionLabel

@Composable
fun SenderScreen(
    state: UiState,
    hasSmsPermission: Boolean,
    onEnableChange: (Boolean) -> Unit,
    onScanQr: () -> Unit,
    onSaveEndpoint: (host: String, port: String, secret: String) -> Unit,
    onRequestSmsPermission: () -> Unit,
    onTestSend: () -> Unit,
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

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = "Send mode",
                themeMode = state.themeMode,
                onThemeMode = onThemeMode,
                onDebugPing = onSendDebugPing,
                onCopyDebugLog = onCopyDebugLog,
                onChangeRole = onChangeRole
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            VersionLabel()
            Spacer(Modifier.height(8.dp))
            Text(
                "Same Wi‑Fi only (encrypted, no cloud). Enter the receiver IP/port, or scan its QR.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(16.dp))

            if (!hasSmsPermission) {
                Text("SMS permission required", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRequestSmsPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant SMS permission")
                }
                Spacer(Modifier.height(16.dp))
            }

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

            Spacer(Modifier.height(24.dp))
            Text("Receiver address", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Port is usually ${ReceiveServer.DEFAULT_PORT}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP address") },
                    placeholder = { Text("192.168.1.20") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Shared secret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onSaveEndpoint(host, port, secret) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save IP / port / secret")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                Text("Or scan QR from receiver")
            }

            Spacer(Modifier.height(24.dp))
            Text("Test", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onTestSend,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.paired
            ) {
                Text("Test send → http://${state.receiverHost.ifBlank { "IP" }}:${state.receiverPort}/sms")
            }
            Text(
                "If Test fails with timeout, the router may block phone‑to‑phone (AP isolation).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
