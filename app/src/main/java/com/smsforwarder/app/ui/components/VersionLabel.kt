package com.smsforwarder.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.smsforwarder.app.BuildConfig

@Composable
fun VersionLabel(modifier: Modifier = Modifier) {
    Text(
        text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = modifier
    )
}
