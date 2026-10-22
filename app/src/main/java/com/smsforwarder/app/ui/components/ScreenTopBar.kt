package com.smsforwarder.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.smsforwarder.app.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(
    title: String,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onDebugPing: () -> Unit,
    onCopyDebugLog: () -> Unit,
    onChangeRole: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        },
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Theme: Auto (system)") },
                        onClick = {
                            menuOpen = false
                            onThemeMode(ThemeMode.SYSTEM)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Theme: Light") },
                        onClick = {
                            menuOpen = false
                            onThemeMode(ThemeMode.LIGHT)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Theme: Dark") },
                        onClick = {
                            menuOpen = false
                            onThemeMode(ThemeMode.DARK)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Write debug ping") },
                        onClick = {
                            menuOpen = false
                            onDebugPing()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy debug log") },
                        onClick = {
                            menuOpen = false
                            onCopyDebugLog()
                        }
                    )
                    if (onChangeRole != null) {
                        DropdownMenuItem(
                            text = { Text("Change role") },
                            onClick = {
                                menuOpen = false
                                onChangeRole()
                            }
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
