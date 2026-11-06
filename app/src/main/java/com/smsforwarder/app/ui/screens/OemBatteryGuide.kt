package com.smsforwarder.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** OEM-specific keep-alive / battery tips (Samsung, Xiaomi, Pixel, generic). */
object OemBatteryGuide {
    fun manufacturerLabel(): String {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        return when {
            m.contains("samsung") -> "Samsung"
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "Xiaomi"
            m.contains("google") || m.contains("pixel") -> "Pixel"
            m.contains("huawei") || m.contains("honor") -> "Huawei"
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> "Oppo/OnePlus"
            else -> Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
        }
    }

    fun tips(): List<String> {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        return when {
            m.contains("samsung") -> listOf(
                "Settings → Apps → SMS Forwarder → Battery → Unrestricted",
                "Settings → Battery → Background usage limits → Never sleeping apps → add this app",
                "Disable any “Put unused apps to sleep” for this app",
                "Keep Wi‑Fi on while sleeping if your phone offers that option"
            )
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> listOf(
                "Settings → Apps → Manage apps → SMS Forwarder → Battery saver → No restrictions",
                "Autostart → enable for SMS Forwarder",
                "Lock the app in Recents so MIUI does not kill it",
                "Turn off MIUI Optimization only if listening still drops (advanced)"
            )
            m.contains("google") || m.contains("pixel") -> listOf(
                "Settings → Apps → SMS Forwarder → App battery usage → Unrestricted",
                "Leave Adaptive Battery on; unrestricted overrides it for this app",
                "Avoid Battery Saver while you need live receive/forward"
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                "Settings → Apps → SMS Forwarder → Battery → Launch → manage manually (all ON)",
                "Add to Protected apps / App launch whitelist",
                "Disable Power-intensive prompt for this app"
            )
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> listOf(
                "Settings → Battery → App battery management → SMS Forwarder → Don’t optimize",
                "Allow auto-launch / background activity",
                "Lock in Recents if the OEM offers it"
            )
            else -> listOf(
                "Settings → Apps → SMS Forwarder → Battery → Unrestricted / Don’t optimize",
                "Disable any “pause unused apps” or similar for this app",
                "On some OEMs, lock the app in Recents so it is not force-stopped"
            )
        }
    }
}

@Composable
fun OemBatteryGuideDialog(
    onDismiss: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep-alive tips — ${OemBatteryGuide.manufacturerLabel()}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Phones often kill background listeners. After allowing unrestricted battery, also check:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OemBatteryGuide.tips().forEachIndexed { i, tip ->
                    Text(
                        "${i + 1}. $tip",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenBatterySettings) { Text("Open battery settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
