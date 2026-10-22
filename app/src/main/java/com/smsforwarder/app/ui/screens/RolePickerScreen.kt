package com.smsforwarder.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.data.ThemeMode
import com.smsforwarder.app.ui.components.ScreenTopBar
import com.smsforwarder.app.ui.components.VersionLabel

@Composable
fun RolePickerScreen(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onDebugPing: () -> Unit,
    onCopyDebugLog: () -> Unit,
    onChooseSender: () -> Unit,
    onChooseReceiver: () -> Unit
) {
    Scaffold(
        topBar = {
            ScreenTopBar(
                title = "SMS WiFi Forwarder",
                themeMode = themeMode,
                onThemeMode = onThemeMode,
                onDebugPing = onDebugPing,
                onCopyDebugLog = onCopyDebugLog,
                onChangeRole = null
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            VersionLabel()
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Forward SMS on the same Wi‑Fi only (encrypted, no cloud). Keep the quiet listen notification on the receive phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onChooseReceiver,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("This phone RECEIVES")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onChooseSender,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("This phone SENDS (has SIM / SMS)")
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Tip: set up Receive first, copy IP/port/secret (or QR), then configure Send.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
