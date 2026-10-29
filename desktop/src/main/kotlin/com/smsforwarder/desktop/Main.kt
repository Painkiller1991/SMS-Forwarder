package com.smsforwarder.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

fun main() = application {
    val prefs = remember { DesktopPrefs() }
    val inbox = remember { InboxStore() }
    val trayState = rememberTrayState()
    val controller = remember {
        ReceiveController(prefs, inbox) { msg ->
            val title = msg.sender
            val body = msg.otp?.let { "OTP $it" } ?: msg.body.take(80)
            trayState.sendNotification(Notification(title, body, Notification.Type.Info))
        }
    }
    var dark by remember { mutableStateOf(false) }
    var windowVisible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(width = 720.dp, height = 820.dp)

    DisposableEffect(Unit) {
        controller.refreshIp()
        onDispose { controller.stop() }
    }

    Tray(
        state = trayState,
        icon = TrayIcon,
        tooltip = "SMS Forwarder PC",
        onAction = { windowVisible = true },
        menu = {
            Item("Show", onClick = { windowVisible = true })
            Item(
                if (controller.listening) "Stop listening" else "Start listening",
                onClick = { controller.setEnabled(!controller.listening) }
            )
            Separator()
            Item("Exit", onClick = {
                controller.stop()
                exitApplication()
            })
        }
    )

    if (windowVisible) {
        Window(
            onCloseRequest = { windowVisible = false },
            title = "SMS Forwarder — PC Receiver",
            state = windowState
        ) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReceiverApp(
                        prefs = prefs,
                        controller = controller,
                        inbox = inbox,
                        dark = dark,
                        onToggleDark = { dark = !dark }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiverApp(
    prefs: DesktopPrefs,
    controller: ReceiveController,
    inbox: InboxStore,
    dark: Boolean,
    onToggleDark: () -> Unit
) {
    val listState = rememberLazyListState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "PC Receiver",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Android sender pairs to this PC over the same Wi‑Fi. Leave listening ON.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Listening", fontWeight = FontWeight.SemiBold)
                        Text(
                            controller.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = controller.listening,
                        onCheckedChange = { controller.setEnabled(it) }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "IP ${controller.localIp.ifBlank { "—" }}  ·  port ${prefs.port}",
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { controller.refreshIp() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh IP")
                    }
                    IconButton(
                        onClick = {
                            val text = "${controller.localIp}:${prefs.port}"
                            copyText(text)
                        },
                        enabled = controller.localIp.isNotBlank()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy address")
                    }
                }
                Text(
                    "Secret: ${prefs.sharedSecret}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            copyText(prefs.pairingPayload(controller.localIp.ifBlank { "0.0.0.0" }))
                        },
                        enabled = controller.localIp.isNotBlank()
                    ) {
                        Text("Copy pair link")
                    }
                    OutlinedButton(
                        onClick = {
                            prefs.sharedSecret = DesktopPrefs.generateSecret()
                            prefs.save()
                            if (controller.listening) {
                                controller.stop()
                                controller.start()
                            }
                        }
                    ) {
                        Text("New secret")
                    }
                    OutlinedButton(onClick = onToggleDark) {
                        Text(if (dark) "Light" else "Dark")
                    }
                }
                Text(
                    "On the Android sender: scan QR or paste IP / port / secret. Allow Windows Firewall for this app on Private networks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Inbox", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row {
                OutlinedButton(
                    onClick = { inbox.clear() },
                    enabled = inbox.messages.isNotEmpty()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (inbox.messages.isEmpty()) {
                Text(
                    "No messages yet. Turn listening ON, then Test send from the Android sender.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(end = 12.dp)
                ) {
                    items(inbox.messages, key = { it.id }) { msg ->
                        MessageCard(msg)
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(
                            URI("https://github.com/Painkiller1991/SMS-Forwarder/releases")
                        )
                    }
                }
            ) {
                Text("GitHub releases")
            }
            Text(
                "v1.0.0 · close window to tray",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
private fun MessageCard(msg: InboxMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    msg.sender,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    msg.timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (msg.otp != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "OTP ${msg.otp}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(msg.body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { copyText(msg.otp ?: msg.body) }
            ) {
                Text(if (msg.otp != null) "Copy OTP" else "Copy text")
            }
        }
    }
}

private fun copyText(text: String) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(text), null)
}
