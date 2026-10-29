package com.smsforwarder.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.data.DayBoundary
import com.smsforwarder.app.data.MessageEntity
import com.smsforwarder.app.data.ReceiverEndpoint
import com.smsforwarder.app.data.ThemeMode
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.net.ForwardClient
import com.smsforwarder.app.net.IncomingSms
import com.smsforwarder.app.net.KeepAliveScheduler
import com.smsforwarder.app.net.LanNetwork
import com.smsforwarder.app.net.ReceiveForegroundService
import com.smsforwarder.app.net.ReceiveServer
import com.smsforwarder.app.net.SenderKeepAliveService
import com.smsforwarder.app.net.WifiIp
import com.smsforwarder.app.pairing.PairingInfo
import com.smsforwarder.app.update.UpdateLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val role: AppRole = AppRole.NONE,
    val senderEnabled: Boolean = false,
    val receiveEnabled: Boolean = false,
    /** At least one enabled endpoint saved. */
    val paired: Boolean = false,
    /** Live /health: any enabled endpoint reachable. */
    val reachable: Boolean = false,
    val reachableCount: Int = 0,
    val endpointCount: Int = 0,
    val pairCheckInProgress: Boolean = false,
    val sharedSecret: String = "",
    val receiverHost: String = "",
    val receiverPort: Int = ReceiveServer.DEFAULT_PORT,
    val endpoints: List<ReceiverEndpoint> = emptyList(),
    val pairingPayload: String = "",
    val localIp: String = "",
    val vpnActive: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val inboxMessages: List<MessageEntity> = emptyList(),
    val historyMessages: List<MessageEntity> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmsForwarderApp
    private val prefs = app.prefs
    private var pairVerifyJob: Job? = null
    private val dayStartFlow = MutableStateFlow(DayBoundary.startOfTodayMillis())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        UpdateLock.clearIfSatisfied(prefs)
        if (UpdateLock.isActive(prefs)) {
            UpdateLock.engage(application, prefs, prefs.updateLockedVersionCode)
        }
        refreshFromPrefs()
        if (!UpdateLock.isActive(prefs)) {
            if (prefs.role == AppRole.RECEIVER && prefs.receiveEnabled) {
                ReceiveForegroundService.start(application)
            }
            if (prefs.role == AppRole.SENDER && prefs.senderEnabled && prefs.endpointConfigured) {
                startSenderKeepAlive()
            }
            if (prefs.role == AppRole.SENDER) {
                verifySenderPairing()
            }
        }
        viewModelScope.launch {
            dayStartFlow.flatMapLatest { dayStart ->
                val dao = app.database.messageDao()
                combine(
                    dao.observeInbox(dayStart),
                    dao.observeHistory(dayStart)
                ) { inbox, history -> inbox to history }
            }.collect { (inbox, history) ->
                _state.update {
                    it.copy(inboxMessages = inbox, historyMessages = history)
                }
            }
        }
    }

    fun refreshDayBoundary() {
        dayStartFlow.value = DayBoundary.startOfTodayMillis()
    }

    fun onForceUpdateLocked() {
        refreshFromPrefs()
    }

    fun refreshFromPrefs() {
        val ctx = getApplication<Application>()
        val ip = WifiIp.localIpv4(ctx).orEmpty()
        val payload = if (ip.isNotBlank()) prefs.pairingPayload(ip) else ""
        val eps = prefs.endpoints
        val enabled = prefs.enabledEndpoints()
        _state.update {
            it.copy(
                role = prefs.role,
                senderEnabled = prefs.senderEnabled,
                receiveEnabled = prefs.receiveEnabled,
                paired = prefs.endpointConfigured,
                sharedSecret = prefs.sharedSecret,
                receiverHost = prefs.receiverHost,
                receiverPort = prefs.receiverPort,
                endpoints = eps,
                endpointCount = enabled.size,
                pairingPayload = payload,
                localIp = ip,
                vpnActive = LanNetwork.vpnActive(ctx),
                themeMode = prefs.themeMode
            )
        }
    }

    fun verifySenderPairing(onDone: ((ok: Boolean, detail: String) -> Unit)? = null) {
        if (prefs.role != AppRole.SENDER) {
            onDone?.invoke(false, "Not in send mode")
            return
        }
        val enabled = prefs.enabledEndpoints()
        if (enabled.isEmpty()) {
            _state.update {
                it.copy(
                    pairCheckInProgress = false,
                    reachable = false,
                    reachableCount = 0,
                    paired = false,
                    endpointCount = 0
                )
            }
            onDone?.invoke(false, "Add a receiver (scan QR or enter IP)")
            return
        }
        pairVerifyJob?.cancel()
        pairVerifyJob = viewModelScope.launch {
            _state.update { it.copy(pairCheckInProgress = true) }
            val result = withContext(Dispatchers.IO) {
                ForwardClient.healthAll(getApplication(), enabled)
            }
            _state.update {
                it.copy(
                    pairCheckInProgress = false,
                    reachable = result.anyOk,
                    reachableCount = result.ok,
                    endpointCount = result.total,
                    paired = prefs.endpointConfigured
                )
            }
            onDone?.invoke(
                result.anyOk,
                if (result.anyOk) {
                    "Reachable ${result.ok}/${result.total}"
                } else {
                    "Unreachable — ${result.failures.joinToString()}"
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
        if (enabled && UpdateLock.isActive(prefs)) {
            refreshFromPrefs()
            return
        }
        prefs.senderEnabled = enabled
        if (enabled && prefs.endpointConfigured) {
            startSenderKeepAlive()
        } else {
            stopSenderKeepAlive()
        }
        refreshFromPrefs()
        if (enabled) verifySenderPairing()
    }

    fun setReceiveEnabled(context: Context, enabled: Boolean): String? {
        if (enabled && UpdateLock.isActive(prefs)) {
            refreshFromPrefs()
            return "Update required before listening"
        }
        prefs.receiveEnabled = enabled
        AgentDebugLog.log(
            hypothesisId = "H_ON",
            location = "MainViewModel.setReceiveEnabled",
            message = "Listening port toggled",
            data = mapOf("enabled" to enabled),
            runId = "listen-toggle"
        )
        if (enabled) {
            prefs.paired = true
            ReceiveForegroundService.start(context)
            KeepAliveScheduler.schedule(context)
        } else {
            KeepAliveScheduler.cancel(context)
            ReceiveForegroundService.stop(context)
        }
        refreshFromPrefs()
        return null
    }

    fun applyPairing(info: PairingInfo, onVerified: ((ok: Boolean, detail: String) -> Unit)? = null) {
        val err = prefs.upsertEndpoint(info.secret, info.host, info.port)
        refreshFromPrefs()
        if (err != null) {
            onVerified?.invoke(false, err)
            return
        }
        if (prefs.senderEnabled) startSenderKeepAlive()
        verifySenderPairing(onVerified)
    }

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
        val err = prefs.upsertEndpoint(s, h, p)
        if (err != null) return err
        refreshFromPrefs()
        if (prefs.senderEnabled) startSenderKeepAlive()
        verifySenderPairing(onVerified)
        return null
    }

    fun removeEndpoint(id: String) {
        prefs.removeEndpoint(id)
        refreshFromPrefs()
        if (!prefs.endpointConfigured && prefs.senderEnabled) {
            stopSenderKeepAlive()
            prefs.senderEnabled = false
            refreshFromPrefs()
        } else {
            verifySenderPairing()
        }
    }

    fun setEndpointEnabled(id: String, enabled: Boolean) {
        prefs.setEndpointEnabled(id, enabled)
        refreshFromPrefs()
        if (!prefs.endpointConfigured && prefs.senderEnabled) {
            stopSenderKeepAlive()
            prefs.senderEnabled = false
            refreshFromPrefs()
        } else if (prefs.senderEnabled && prefs.endpointConfigured) {
            startSenderKeepAlive()
            verifySenderPairing()
        }
    }

    fun clearInbox() {
        viewModelScope.launch {
            app.database.messageDao()
                .clearTodayUnpinned(DayBoundary.startOfTodayMillis())
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            app.database.messageDao()
                .clearHistory(DayBoundary.startOfTodayMillis())
        }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch {
            app.database.messageDao().deleteById(id)
        }
    }

    fun setMessagePinned(id: Long, pinned: Boolean) {
        viewModelScope.launch {
            app.database.messageDao().setPinned(id, pinned)
        }
    }

    fun testSendToReceiver(onDone: (ok: Boolean, detail: String) -> Unit) {
        viewModelScope.launch {
            if (!prefs.endpointConfigured) {
                onDone(false, "Add a receiver first")
                return@launch
            }
            val body = "TEST LAN — OTP 123456 — ${System.currentTimeMillis()}"
            val result = withContext(Dispatchers.IO) {
                ForwardClient.forwardToAll(
                    context = getApplication(),
                    endpoints = prefs.enabledEndpoints(),
                    sender = "TEST",
                    body = body
                )
            }
            AgentDebugLog.log(
                hypothesisId = "LAN",
                location = "MainViewModel.testSendToReceiver",
                message = "LAN test fan-out",
                data = mapOf(
                    "ok" to result.ok,
                    "total" to result.total,
                    "failures" to result.failures.joinToString()
                ),
                runId = "lan"
            )
            _state.update {
                it.copy(
                    reachable = result.anyOk,
                    reachableCount = result.ok,
                    endpointCount = result.total
                )
            }
            onDone(result.anyOk, result.summary())
        }
    }

    fun testSendFromReceiver(onDone: (ok: Boolean, detail: String) -> Unit) {
        if (!prefs.receiveEnabled) {
            onDone(false, "Turn Receive ON first")
            return
        }
        viewModelScope.launch {
            val body = "TEST local — OTP 123456 — ${System.currentTimeMillis()}"
            withContext(Dispatchers.IO) {
                IncomingSms.accept(
                    app = app,
                    sender = "TEST",
                    body = body,
                    timestamp = System.currentTimeMillis()
                )
            }
            onDone(true, "OK — test message injected. Check inbox + notification.")
        }
    }
}
