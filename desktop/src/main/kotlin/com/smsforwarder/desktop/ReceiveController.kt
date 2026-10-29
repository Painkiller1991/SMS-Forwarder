package com.smsforwarder.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smsforwarder.app.net.ReceiveServer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class ReceiveController(
    private val prefs: DesktopPrefs,
    private val inbox: InboxStore,
    private val onMessage: (InboxMessage) -> Unit
) {
    private val serverRef = AtomicReference<ReceiveServer?>(null)
    private val seenMsgIds = ConcurrentHashMap.newKeySet<String>()

    var listening by mutableStateOf(false)
        private set
    var status by mutableStateOf("Stopped")
        private set
    var localIp by mutableStateOf(LanIp.localIpv4().orEmpty())
        private set

    fun refreshIp() {
        localIp = LanIp.localIpv4().orEmpty()
        if (listening) {
            status = listeningStatus()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    fun start() {
        if (serverRef.get() != null) {
            listening = true
            status = listeningStatus()
            return
        }
        refreshIp()
        val host = localIp.ifBlank { ReceiveServer.BIND_ALL }
        try {
            val server = ReceiveServer(
                hostname = host,
                port = prefs.port,
                sharedSecretProvider = { prefs.sharedSecret },
                onSms = { sender, body, timestamp, msgId ->
                    if (!msgId.isNullOrBlank() && !seenMsgIds.add(msgId)) {
                        return@ReceiveServer "duplicate"
                    }
                    if (seenMsgIds.size > 500) {
                        seenMsgIds.clear()
                        if (!msgId.isNullOrBlank()) seenMsgIds.add(msgId)
                    }
                    val msg = inbox.add(sender, body, timestamp)
                    onMessage(msg)
                    "stored"
                }
            )
            server.startListening()
            serverRef.set(server)
            listening = true
            status = listeningStatus()
        } catch (e: Exception) {
            listening = false
            status = "Failed: ${e.message}"
            stopQuiet()
        }
    }

    fun stop() {
        stopQuiet()
        listening = false
        status = "Stopped"
    }

    private fun stopQuiet() {
        val s = serverRef.getAndSet(null) ?: return
        runCatching { s.closeAllConnections() }
        runCatching { s.stop() }
    }

    private fun listeningStatus(): String {
        val ip = localIp.ifBlank { "0.0.0.0" }
        return "Listening $ip:${prefs.port}"
    }
}
