package com.smsforwarder.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smsforwarder.app.data.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    title: String,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onDebugPing: () -> Unit,
    onCopyDebugLog: () -> Unit,
    onClearDebugLog: () -> Unit = {},
    readDebugLog: () -> String = { "" },
    onChangeRole: (() -> Unit)? = null,
    onOpenHistory: (() -> Unit)? = null,
    pendingSyncCount: Int? = null,
    onPendingSync: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val updateOffer = LocalUpdateOffer.current
    val pendingUpdate = updateOffer?.available
    var showLogDialog by remember { mutableStateOf(false) }
    var logPreview by remember { mutableStateOf("") }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Debug log (recent)") },
            text = {
                val scroll = rememberScrollState()
                Text(
                    text = logPreview.ifBlank { "(empty — trigger an action, then Show again)" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(scroll),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCopyDebugLog()
                        showLogDialog = false
                    }
                ) { Text("Copy all") }
            },
            dismissButton = {
                TextButton(onClick = { showLogDialog = false }) { Text("Close") }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
                    Text(
                        text = "SMS WiFi Forwarder",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    ThemeSegmentedControl(
                        themeMode = themeMode,
                        onThemeMode = onThemeMode
                    )
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    if (onOpenHistory != null) {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.History, contentDescription = null) },
                            label = { Text("History") },
                            selected = false,
                            onClick = {
                                closeDrawer()
                                onOpenHistory()
                            }
                        )
                    }
                    if (pendingSyncCount != null && onPendingSync != null) {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                            label = { Text("Pending sync ($pendingSyncCount)") },
                            selected = false,
                            onClick = {
                                closeDrawer()
                                onPendingSync()
                            }
                        )
                    }
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                        label = { Text("Check for updates") },
                        selected = false,
                        onClick = {
                            closeDrawer()
                            updateOffer?.requestManualCheck()
                        }
                    )
                    if (pendingUpdate != null) {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                            label = { Text("Update to ${pendingUpdate.versionName}") },
                            selected = false,
                            onClick = {
                                closeDrawer()
                                updateOffer?.openFromDrawer()
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Debug log",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                        label = { Text("Show debug log") },
                        selected = false,
                        onClick = {
                            closeDrawer()
                            val full = readDebugLog()
                            val lines = full.lines().filter { it.isNotBlank() }
                            logPreview = lines.takeLast(80).joinToString("\n")
                            showLogDialog = true
                        }
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        label = { Text("Copy debug log") },
                        selected = false,
                        onClick = {
                            closeDrawer()
                            onCopyDebugLog()
                        }
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
                        label = { Text("Clear debug log") },
                        selected = false,
                        onClick = {
                            closeDrawer()
                            onClearDebugLog()
                        }
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                        label = { Text("Write debug ping") },
                        selected = false,
                        onClick = {
                            closeDrawer()
                            onDebugPing()
                        }
                    )

                    if (onChangeRole != null) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        NavigationDrawerItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                            label = { Text("Change role") },
                            selected = false,
                            onClick = {
                                closeDrawer()
                                onChangeRole()
                            }
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            title,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open menu")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            content = content
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSegmentedControl(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        ThemeMode.SYSTEM to ("Auto" to Icons.Default.PhoneAndroid),
        ThemeMode.LIGHT to ("Light" to Icons.Default.LightMode),
        ThemeMode.DARK to ("Dark" to Icons.Default.DarkMode)
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, labelIcon) ->
            val (label, icon) = labelIcon
            SegmentedButton(
                selected = themeMode == mode,
                onClick = { onThemeMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {
                    SegmentedButtonDefaults.Icon(active = themeMode == mode) {
                        Icon(icon, contentDescription = null)
                    }
                },
                label = { Text(label) }
            )
        }
    }
}
