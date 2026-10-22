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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.smsforwarder.app.net.ReceiveServer
import com.smsforwarder.app.pairing.QrBitmap
import com.smsforwarder.app.ui.UiState
import com.smsforwarder.app.ui.components.VersionLabel
import java.text.DateFormat
import java.util.Date

@Composable
fun ReceiverScreen(
    state: UiState,
    messages: List<MessageEntity>,
    hasNotificationPermission: Boolean,
    onToggleReceive: (Boolean) -> Unit,
    onOpenAutofill: () -> Unit,
    onClearInbox: () -> Unit,
    onTestSend: () -> Unit,
    onBatteryUnrestricted: () -> Unit,
    onCopyAddress: () -> Unit,
    onSendDebugPing: () -> Unit,
    onCopyDebugLog: () -> Unit,
    onChangeRole: () -> Unit
) {
    val qrBitmap: Bitmap? = remember(state.pairingPayload) {
        state.pairingPayload.takeIf { it.isNotBlank() }?.let { QrBitmap.encode(it) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Receive mode", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            VersionLabel()
            Spacer(Modifier.height(8.dp))
            Text(
                "Local Wi‑Fi only. Android has no silent always‑on socket — the quiet notification is the system background task. Home or swipe is OK; do not Force Stop. Use unrestricted battery.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        item {
            Text("This phone’s address", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.localIp.isNotBlank()) state.localIp else "No Wi‑Fi IP",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            )
            Text(
                "Port  ${ReceiveServer.DEFAULT_PORT}",
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Type these on the sender, or scan the QR below.",
                style = MaterialTheme.typography.bodySmall
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
                    style = MaterialTheme.typography.bodySmall
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
                    Text("Always-on receive", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.receiveEnabled) "Foreground service listening — keep notification"
                        else "Turn on to listen in background",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = state.receiveEnabled, onCheckedChange = onToggleReceive)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onTestSend,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.receiveEnabled
            ) {
                Text("Test send (to this phone)")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBatteryUnrestricted, modifier = Modifier.fillMaxWidth()) {
                Text("Allow unrestricted battery (needed when closed)")
            }
            Text(
                "1) Receive ON  2) Unrestricted battery  3) Do not dismiss the listen notification  4) Home (don’t Force Stop).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
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
                Text("Connect to Wi‑Fi to show QR", style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Text("OTP autofill for other apps", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Enable this app as Autofill, then focus an OTP field after an SMS arrives.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenAutofill, modifier = Modifier.fillMaxWidth()) {
                Text("Open Autofill settings")
            }
        }

        item {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Inbox", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = onClearInbox) { Text("Clear") }
            }
        }

        if (messages.isEmpty()) {
            item { Text("No forwarded SMS yet.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(messages, key = { it.id }) { msg ->
                MessageCard(msg)
            }
        }

        item {
            Text("Debug", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSendDebugPing, modifier = Modifier.fillMaxWidth()) {
                Text("Write debug ping (on phone)")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCopyDebugLog, modifier = Modifier.fillMaxWidth()) {
                Text("Copy debug log → paste in chat")
            }
        }

        item {
            TextButton(onClick = onChangeRole) { Text("Change role") }
        }
    }
}

@Composable
private fun MessageCard(msg: MessageEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(msg.sender, fontWeight = FontWeight.SemiBold)
            Text(
                DateFormat.getDateTimeInstance().format(Date(msg.receivedAt)),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(6.dp))
            Text(msg.body)
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
