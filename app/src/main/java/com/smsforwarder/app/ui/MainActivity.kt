package com.smsforwarder.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.integration.android.IntentIntegrator
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.net.ReceiveForegroundService
import com.smsforwarder.app.net.ReceiveServer
import com.smsforwarder.app.net.SenderKeepAliveService
import com.smsforwarder.app.net.ServiceWatchdog
import com.smsforwarder.app.notify.InboxNotifier
import com.smsforwarder.app.pairing.PairingParser
import com.smsforwarder.app.ui.components.AppUpdateHost
import com.smsforwarder.app.ui.components.LocalUpdateOffer
import com.smsforwarder.app.ui.components.rememberUpdateOfferController
import com.smsforwarder.app.ui.screens.HistoryScreen
import com.smsforwarder.app.ui.screens.ReceiverScreen
import com.smsforwarder.app.ui.screens.RolePickerScreen
import com.smsforwarder.app.ui.screens.SenderScreen
import com.smsforwarder.app.ui.theme.SmsForwarderTheme
import com.smsforwarder.app.update.UpdateLock

class MainActivity : ComponentActivity() {
    private var onScanResult: ((String?) -> Unit)? = null

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intentResult = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
        onScanResult?.invoke(intentResult?.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.state.collectAsState()
            SmsForwarderTheme(themeMode = state.themeMode) {
                AppRoot(
                    activity = this,
                    vm = vm,
                    requestScan = { callback ->
                        onScanResult = callback
                        val intent = IntentIntegrator(this)
                            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                            .setPrompt("Scan receiver QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                            .createScanIntent()
                        scanLauncher.launch(intent)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        val prefs = (application as? SmsForwarderApp)?.prefs
        if (prefs != null && !UpdateLock.isActive(prefs)) {
            when (prefs.role) {
                AppRole.RECEIVER -> if (prefs.receiveEnabled) {
                    ServiceWatchdog.scheduleSoon(applicationContext)
                    runCatching { ReceiveForegroundService.ensure(applicationContext) }
                }
                AppRole.SENDER -> if (prefs.senderEnabled && prefs.endpointConfigured) {
                    ServiceWatchdog.scheduleSoon(applicationContext)
                    runCatching { SenderKeepAliveService.ensure(applicationContext) }
                }
                else -> Unit
            }
        }
        super.onDestroy()
    }

    fun openAutofillSettings() {
        val am = getSystemService(AutofillManager::class.java)
        if (am == null || !am.isAutofillSupported) {
            Toast.makeText(this, "Autofill not supported on this device", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            Toast.makeText(
                this,
                "Open Settings → Autofill and choose SMS WiFi Forwarder",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun hasSmsPermission(): Boolean {
        val receive = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        return receive && read
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun needsBatteryUnrestricted(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    fun needsAutofillSetup(): Boolean {
        val am = getSystemService(AutofillManager::class.java) ?: return false
        if (!am.isAutofillSupported) return false
        val selected = Settings.Secure.getString(contentResolver, "autofill_service")
        return selected.isNullOrBlank() || !selected.contains(packageName)
    }

    fun sendDebugPing() {
        AgentDebugLog.sendTestPing { ok, detail ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Debug ping: $detail" else "Debug ping FAILED: $detail",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun copyDebugLog() {
        val text = AgentDebugLog.readAll(this)
        if (text.isBlank()) {
            Toast.makeText(this, "No debug log yet — use menu → Write debug ping first", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("debug-log", text))
        Toast.makeText(
            this,
            "Debug log copied (${text.lines().size} lines) — paste in chat",
            Toast.LENGTH_LONG
        ).show()
    }

    fun requestBatteryUnrestricted() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Battery already unrestricted", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

@Composable
private fun AppRoot(
    activity: MainActivity,
    vm: MainViewModel,
    requestScan: ((String?) -> Unit) -> Unit
) {
    val state by vm.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasSmsPermission by remember { mutableStateOf(activity.hasSmsPermission()) }
    var hasNotificationPermission by remember { mutableStateOf(activity.hasNotificationPermission()) }
    var needsBatteryUnrestricted by remember { mutableStateOf(activity.needsBatteryUnrestricted()) }
    var needsAutofillSetup by remember { mutableStateOf(activity.needsAutofillSetup()) }

    fun refreshDeviceFlags() {
        hasSmsPermission = activity.hasSmsPermission()
        hasNotificationPermission = activity.hasNotificationPermission()
        needsBatteryUnrestricted = activity.needsBatteryUnrestricted()
        needsAutofillSetup = activity.needsAutofillSetup()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshDeviceFlags()
                vm.refreshDayBoundary()
                if (vm.state.value.role == AppRole.SENDER) {
                    vm.verifySenderPairing()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshDeviceFlags()
    }

    fun requestSenderPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    fun requestReceiverPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    val updateOffer = rememberUpdateOfferController()
    CompositionLocalProvider(LocalUpdateOffer provides updateOffer) {
    Box(modifier = Modifier.fillMaxSize()) {
    AppUpdateHost(
        activity = activity,
        offer = updateOffer,
        onForceLockApplied = { vm.onForceUpdateLocked() }
    )
    when (state.role) {
        AppRole.NONE -> RolePickerScreen(
            themeMode = state.themeMode,
            onThemeMode = vm::setThemeMode,
            onDebugPing = { activity.sendDebugPing() },
            onCopyDebugLog = { activity.copyDebugLog() },
            onChooseSender = {
                requestSenderPermissions()
                vm.setRole(AppRole.SENDER)
            },
            onChooseReceiver = {
                requestReceiverPermissions()
                vm.setRole(AppRole.RECEIVER)
            }
        )

        AppRole.SENDER -> SenderScreen(
            state = state,
            hasSmsPermission = hasSmsPermission,
            needsBatteryUnrestricted = needsBatteryUnrestricted,
            onEnableChange = vm::setSenderEnabled,
            onScanQr = {
                requestScan { contents ->
                    val info = contents?.let { PairingParser.parse(it) }
                    if (info == null) {
                        Toast.makeText(activity, "Invalid QR", Toast.LENGTH_SHORT).show()
                    } else {
                        vm.applyPairing(info) { ok, detail ->
                            Toast.makeText(activity, detail, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onSaveEndpoint = { host, port, secret ->
                val err = vm.saveManualEndpoint(host, port, secret) { _, detail ->
                    Toast.makeText(activity, detail, Toast.LENGTH_LONG).show()
                }
                if (err != null) {
                    Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                }
            },
            onRequestSmsPermission = { requestSenderPermissions() },
            onTestSend = {
                vm.testSendToReceiver { _, detail ->
                    Toast.makeText(activity, detail, Toast.LENGTH_LONG).show()
                }
            },
            onBatteryUnrestricted = { activity.requestBatteryUnrestricted() },
            onThemeMode = vm::setThemeMode,
            onSendDebugPing = { activity.sendDebugPing() },
            onCopyDebugLog = { activity.copyDebugLog() },
            onChangeRole = { vm.setRole(AppRole.NONE) }
        )

        AppRole.RECEIVER -> {
            var showHistory by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { vm.refreshPairingQr() }
            val copyOtp: (String) -> Unit = { otp ->
                InboxNotifier.copyOtp(activity, otp)
                Toast.makeText(activity, "OTP copied", Toast.LENGTH_SHORT).show()
            }
            val copyBody: (String) -> Unit = { body ->
                val cm = activity.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("sms", body))
                Toast.makeText(activity, "Message copied", Toast.LENGTH_SHORT).show()
            }
            if (showHistory) {
                HistoryScreen(
                    messages = state.historyMessages,
                    onBack = { showHistory = false },
                    onClearHistory = vm::clearHistory,
                    onCopyOtp = copyOtp,
                    onCopyBody = copyBody,
                    onPinToInbox = { msg ->
                        vm.setMessagePinned(msg.id, true)
                        Toast.makeText(activity, "Pinned to inbox", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteMessage = { id -> vm.deleteMessage(id) }
                )
            } else {
                ReceiverScreen(
                    state = state,
                    messages = state.inboxMessages,
                    hasHistory = state.historyMessages.isNotEmpty(),
                    hasNotificationPermission = hasNotificationPermission,
                    needsBatteryUnrestricted = needsBatteryUnrestricted,
                    needsAutofillSetup = needsAutofillSetup,
                    onToggleReceive = { enabled ->
                        requestReceiverPermissions()
                        vm.setReceiveEnabled(activity, enabled)
                    },
                    onOpenAutofill = { activity.openAutofillSettings() },
                    onClearInbox = vm::clearInbox,
                    onOpenHistory = { showHistory = true },
                    onCopyOtp = copyOtp,
                    onCopyBody = copyBody,
                    onTogglePin = { msg ->
                        vm.setMessagePinned(msg.id, !msg.pinned)
                    },
                    onDeleteMessage = { id ->
                        vm.deleteMessage(id)
                    },
                    onTestSend = {
                        vm.testSendFromReceiver { _, detail ->
                            Toast.makeText(activity, detail, Toast.LENGTH_LONG).show()
                        }
                    },
                    onBatteryUnrestricted = { activity.requestBatteryUnrestricted() },
                    onCopyAddress = {
                        val ip = state.localIp
                        if (ip.isBlank()) {
                            Toast.makeText(activity, "No Wi‑Fi IP yet", Toast.LENGTH_SHORT).show()
                        } else {
                            val text =
                                "IP: $ip\nPort: ${ReceiveServer.DEFAULT_PORT}\nSecret: ${state.sharedSecret}"
                            val cm = activity.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("lan-address", text))
                            Toast.makeText(activity, "Copied IP, port, secret", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onThemeMode = vm::setThemeMode,
                    onSendDebugPing = { activity.sendDebugPing() },
                    onCopyDebugLog = { activity.copyDebugLog() },
                    onChangeRole = { vm.setRole(AppRole.NONE) }
                )
            }
        }
    }
    } // Box
    } // CompositionLocalProvider
}
