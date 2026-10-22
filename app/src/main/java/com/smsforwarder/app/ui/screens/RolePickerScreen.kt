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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.ui.components.VersionLabel

@Composable
fun RolePickerScreen(
    onChooseSender: () -> Unit,
    onChooseReceiver: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "SMS WiFi Forwarder",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        VersionLabel()
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Forward SMS over the internet (encrypted). Works without same‑Wi‑Fi IP. Keep the quiet notification on so it works when the app is closed.",
            style = MaterialTheme.typography.bodyMedium
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
            text = "Tip: set up Receive on the phone that should show OTPs, then scan its QR from the Send phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}
