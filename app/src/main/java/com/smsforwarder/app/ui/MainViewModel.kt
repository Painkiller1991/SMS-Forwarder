package com.smsforwarder.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.data.MessageEntity
import com.smsforwarder.app.data.ThemeMode
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.net.ForwardClient
import com.smsforwarder.app.net.KeepAliveScheduler
import com.smsforwarder.app.net.ReceiveForegroundService
import com.smsforwarder.app.net.ReceiveServer
import com.smsforwarder.app.net.SenderKeepAliveService
import com.smsforwarder.app.net.WifiIp
import com.smsforwarder.app.pairing.PairingInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val role: AppRole = AppRole.NONE,
    val senderEnabled: Boolean = false,
    val receiveEnabled: Boolean = false,
    /** Endpoint credentials saved (durable). Not the same as live reachability. */
    val paired: Boolean = false,
    /** Live /health result for StatusChip only. */
    val reachable: Boolean = false,
    val pairCheckInProgress: Boolean = false,
    val sharedSecret: String = "",
    val receiverHost: String = "",
    val receiverPort: Int = ReceiveServer.DEFAULT_PORT,
    val pairingPayload: String = "",
    val localIp: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val messages: List<MessageEntity> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmsForwarderApp
    private val prefs = app.prefs
    private var pairVerifyJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshFromPrefs()
        if (prefs.role == AppRole.RECEIVER && prefs.receiveEnabled) {
            ReceiveForegroundService.start(application)
        }
        if (prefs.role == AppRole.SENDER && prefs.senderEnabled && prefs.endpointConfigured) {
            startSenderKeepAlive()
        }
        if (prefs.role == AppRole.SENDER) {
            verifySenderPairing()
        }
        viewModelScope.launch {
            app.database.messageDao().observeAll().collect { list ->
                _state.update { it.copy(messages = list) }
            }
        }
    }

    fun refreshFromPrefs() {
        val ctx = getApplication<Application>()
        val ip = WifiIp.localIpv4(ctx).orEmpty()
        val payload = if (ip.isNotBlank()) prefs.pairingPayload(ip) else ""
        _state.update {
            it.copy(
                role = prefs.role,
                senderEnabled = prefs.senderEnabled,
                receiveEnabled = prefs.receiveEnabled,
                paired = prefs.endpointConfigured,
                sharedSecret = prefs.sharedSecret,
                receiverHost = prefs.receiverHost,
                receiverPort = prefs.receiverPort,
                pairingPayload = payload,
                localIp = ip,
                themeMode = prefs.themeMode
            )
        }
    }

    /**
     * Hits receiver `/health` for UI StatusChip only.
     * Never stops keep-alive or clears endpoint-configured on failure.
     */
    fun verifySenderPairing(onDone: ((ok: Boolean, detail: String) -> Unit)? = null) {
        if (prefs.role != AppRole.SENDER) {
            onDone?.invoke(false, "Not in send mode")
            return
        }
        val host = prefs.receiverHost.trim()
        val port = prefs.receiverPort
        val secret = prefs.sharedSecret
        if (host.isBlank() || secret.length < 16 || !prefs.paired) {
            _state.update {
                it.copy(pairCheckInProgress = false, reachable = false, paired = false)
            }
            onDone?.invoke(false, "Enter IP, port, and secret")
            return
        }
        pairVerifyJob?.cancel()
        pairVerifyJob = viewModelScope.launch {
            _state.update { it.copy(pairCheckInProgress = true) }
            val result = withContext(Dispatchers.IO) {
                ForwardClient.health(host, port)
            }
            val ok = result.isSuccess
            _state.update {
                it.copy(
                    pairCheckInProgress = false,
                    reachable = ok,
                    paired = prefs.endpointConfigured
                )
            }
            onDone?.invoke(
                ok,
                if (ok) {
                    "Paired — reachable at $host:$port"
                } else {
                    "Unreachable — ${result.exceptionOrNull()?.message ?: "cannot reach receiver"}"
                }
            )
        }
    }

    private fun startSenderKeepAlive() {
        val ctx = getApplication<Application>()
        SenderKeepAliveService.start(ctx)
        KeepAliveScheduler.schedule(ctx)
    }

    private fun stopSenderKeepAlive() {
        val ctx = getApplication<Application>()
        SenderKeepAliveService.stop(ctx)
        KeepAliveScheduler.cancel(ctx)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode
        refreshFromPrefs()
    }

    fun refreshPairingQr() {
        prefs.paired = true
        refreshFromPrefs()
    }

    fun setRole(role: AppRole) {
        val ctx = getApplication<Application>()
        if (role != AppRole.RECEIVER && prefs.receiveEnabled) {
            ReceiveForegroundService.stop(ctx)
            prefs.receiveEnabled = false
            KeepAliveScheduler.cancel(ctx)
        }
        if (role != AppRole.SENDER) {
            if (prefs.senderEnabled) stopSenderKeepAlive()
            prefs.senderEnabled = false
        }
        prefs.role = role
        if (role == AppRole.RECEIVER) {
            prefs.paired = true
            refreshFromPrefs()
        } else if (role == AppRole.SENDER) {
            refreshFromPrefs()
            if (prefs.senderEnabled && prefs.endpointConfigured) {
                startSenderKeepAlive()
            }
            verifySenderPairing()
        } else {
            refreshFromPrefs()
        }
    }

    fun setSenderEnabled(enabled: Boolean) {
        prefs.senderEnabled = enabled
        if (enabled && prefs.endpointConfigured) {
            startSenderKeepAlive()
        } else {
            stopSenderKeepAlive()
        }
        refreshFromPrefs()
        if (enabled) verifySenderPairing()
    }

    fun setReceiveEnabled(context: Context, enabled: Boolean) {
        prefs.receiveEnabled = enabled
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H_ON",
            location = "MainViewModel.setReceiveEnabled",
            message = "Listening port toggled",
            data = mapOf("enabled" to enabled),
            runId = "listen-toggle"
        )
        // #endregion
        if (enabled) {
            prefs.paired = true
            ReceiveForegroundService.start(context)
            KeepAliveScheduler.schedule(context)
        } else {
            KeepAliveScheduler.cancel(context)
            ReceiveForegroundService.stop(context)
        }
        refreshFromPrefs()
    }

    fun applyPairing(info: PairingInfo, onVerified: ((ok: Boolean, detail: String) -> Unit)? = null) {
        prefs.applyPairing(info.secret, info.host, info.port)
        refreshFromPrefs()
        if (prefs.senderEnabled) startSenderKeepAlive()
        verifySenderPairing(onVerified)
    }

    /**
     * Manual IP / port / secret. Returns a sync validation error, or null if saved —
     * then [verifySenderPairing] reports reachability via [onVerified] (UI only).
     */
    fun saveManualEndpoint(
        host: String,
        portText: String,
        secret: String,
        onVerified: ((ok: Boolean, detail: String) -> Unit)? = null
    ): String? {
        val h = host.trim()
        val s = secret.trim()
        val p = portText.trim().toIntOrNull() ?: return "Port must be a number (e.g. 18765)"
        if (h.isBlank()) return "Enter receiver IP (shown on receiver phone)"
        if (s.length < 16) return "Secret too short — copy from receiver or scan QR"
        if (p !in 1..65535) return "Port out of range"
        prefs.applyPairing(s, h, p)
        refreshFromPrefs()
        if (prefs.senderEnabled) startSenderKeepAlive()
        verifySenderPairing(onVerified)
        return null
    }

    fun clearInbox() {
        viewModelScope.launch {
            app.database.messageDao().clear()
        }
    }

    fun testSendToReceiver(onDone: (ok: Boolean, detail: String) -> Unit) {
        viewModelScope.launch {
            if (!prefs.endpointConfigured) {
                onDone(false, "Save IP + port + secret first")
                return@launch
            }
            val body = "TEST LAN — OTP 123456 — ${System.currentTimeMillis()}"
            val result = withContext(Dispatchers.IO) {
                ForwardClient.forward(
                    host = prefs.receiverHost,
                    port = prefs.receiverPort,
                    secret = prefs.sharedSecret,
                    sender = "TEST",
                    body = body
                )
            }
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "LAN",
                location = "MainViewModel.testSendToReceiver",
                message = "LAN test publish",
                data = mapOf(
                    "success" to result.isSuccess,
                    "host" to prefs.receiverHost,
                    "error" to result.exceptionOrNull()?.message?.take(160)
                ),
                runId = "lan"
            )
            // #endregion
            _state.update { it.copy(reachable = result.isSuccess) }
            if (result.isSuccess) {
                onDone(true, "OK — sent to ${prefs.receiverHost}:${prefs.receiverPort}")
            } else {
                onDone(
                    false,
                    "FAIL — ${result.exceptionOrNull()?.message}. Same Wi‑Fi? AP isolation off?"
                )
            }
        }
    }

    fun testSendFromReceiver(onDone: (ok: Boolean, detail: String) -> Unit) {
        if (!prefs.receiveEnabled) {
            onDone(false, "Turn Receive ON first")
            return
        }
        viewModelScope.launch {
            val ip = WifiIp.localIpv4(getApplication()) ?: "127.0.0.1"
            val body = "TEST LAN self — OTP 123456 — ${System.currentTimeMillis()}"
            val result = withContext(Dispatchers.IO) {
                ForwardClient.forward(
                    host = ip,
                    port = ReceiveServer.DEFAULT_PORT,
                    secret = prefs.sharedSecret,
                    sender = "TEST",
                    body = body
                )
            }
            if (result.isSuccess) {
                onDone(true, "OK — local loopback. Check inbox.")
            } else {
                onDone(false, "FAIL — ${result.exceptionOrNull()?.message}")
            }
        }
    }
}
