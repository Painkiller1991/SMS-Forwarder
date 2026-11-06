package com.smsforwarder.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HotspotGuideCard(
    isSender: Boolean,
    hotspotActive: Boolean = false,
    hotspotSsid: String = "",
    hotspotPassword: String = "",
    hotspotError: String? = null,
    onHotspotLan: Boolean = false,
    onStartHotspot: (() -> Unit)? = null,
    onStopHotspot: (() -> Unit)? = null,
    onOpenSystemHotspot: (() -> Unit)? = null,
    onCopyCredentials: ((String) -> Unit)? = null
) {
    TonalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.WifiTethering,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("No shared Wi‑Fi?", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        if (isSender) {
            Text(
                "1. Start a hotspot on this phone (below or system settings).\n" +
                    "2. On the receiver, join that Wi‑Fi.\n" +
                    "3. On the receiver, turn Receive ON and show the QR.\n" +
                    "4. Scan that QR here — same LAN method as usual.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (hotspotActive && hotspotSsid.isNotBlank()) {
                Text("Hotspot ON", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("SSID: $hotspotSsid", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        if (hotspotPassword.isNotBlank()) {
                            Text(
                                "Password: $hotspotPassword",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (onCopyCredentials != null && hotspotPassword.isNotBlank()) {
                        IconButton(
                            onClick = {
                                onCopyCredentials("SSID: $hotspotSsid\nPassword: $hotspotPassword")
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onStopHotspot?.invoke() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop hotspot")
                }
            } else {
                FilledTonalButton(
                    onClick = { onStartHotspot?.invoke() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start local hotspot")
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { onOpenSystemHotspot?.invoke() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open system Hotspot settings")
            }
            hotspotError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                "1. On the sender, start a hotspot (or system Hotspot).\n" +
                    "2. Join that Wi‑Fi on this phone.\n" +
                    "3. Turn Receive ON — the QR IP should look like 192.168.x.x.\n" +
                    "4. Let the sender scan this QR.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onHotspotLan) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Looks like you are on a hotspot LAN — good for pairing.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Receiver often has no internet while on the hotspot. Keep the hotspot on while forwarding.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
