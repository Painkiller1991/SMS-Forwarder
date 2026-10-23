package com.smsforwarder.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.BuildConfig
import com.smsforwarder.app.update.AvailableUpdate

@Composable
fun UpdateAvailableDialog(
    update: AvailableUpdate,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit
) {
    val forced = update.forceUpdate
    AlertDialog(
        onDismissRequest = { if (!forced) onLater() },
        title = {
            Text(
                if (forced) "Update required" else "Update available",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (forced) {
                        "Version ${update.versionName} is required. The app cannot continue until you install it."
                    } else {
                        "New version ${update.versionName} is ready."
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Current v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Changelog",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (update.changelog.isEmpty()) {
                            Text(
                                "No changelog included for this release.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            update.changelog.forEach { line ->
                                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                    Text(
                                        "•",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        // Stack all actions in confirmButton — dismissButton clips a tall Column
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download and install")
                }
                if (!forced) {
                    TextButton(onClick = onLater) {
                        Text("Later")
                    }
                    TextButton(onClick = onSkip) {
                        Text("Skip this version")
                    }
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
fun UpdateProgressDialog(
    progress: Float,
    onCancel: () -> Unit,
    allowCancel: Boolean = true
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Downloading update", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                if (progress < 0f) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Downloading APK…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Downloading APK ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (allowCancel) {
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    )
}
