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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.integration.android.IntentIntegrator
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.net.ReceiveForegroundService
import com.smsforwarder.app.net.ServiceWatchdog
import com.smsforwarder.app.pairing.PairingParser
import com.smsforwarder.app.ui.screens.ReceiverScreen
import com.smsforwarder.app.ui.screens.RolePickerScreen
import com.smsforwarder.app.ui.screens.SenderScreen
import com.smsforwarder.app.ui.theme.SmsForwarderTheme

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
            SmsForwarderTheme {
                AppRoot(
                    activity = this,
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
        // If UI process is dying while receive is on, keep :recv alive / restart soon
        val prefs = (application as? SmsForwarderApp)?.prefs
        if (prefs?.role == AppRole.RECEIVER && prefs.receiveEnabled) {
            ServiceWatchdog.scheduleSoon(applicationContext)
            runCatching { ReceiveForegroundService.ensure(applicationContext) }
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
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
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
            Toast.makeText(this, "No debug log yet — tap Write debug ping first", Toast.LENGTH_SHORT)
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
    requestScan: ((String?) -> Unit) -> Unit
) {
    val vm: MainViewModel = viewModel()
    val state by vm.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

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

    when (state.role) {
        AppRole.NONE -> RolePickerScreen(
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
            hasSmsPermission = activity.hasSmsPermission(),
            onEnableChange = vm::setSenderEnabled,
            onScanQr = {
                requestScan { contents ->
                    val info = contents?.let { PairingParser.parse(it) }
                    if (info == null) {
                        Toast.makeText(activity, "Invalid QR", Toast.LENGTH_SHORT).show()
                    } else {
                        vm.applyPairing(info)
                        Toast.makeText(activity, "Paired → ${info.host}:${info.port}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSaveEndpoint = { host, port, secret ->
                val err = vm.saveManualEndpoint(host, port, secret)
                if (err != null) {
                    Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, "Saved → $host:$port", Toast.LENGTH_SHORT).show()
                }
            },
            onRequestSmsPermission = { requestSenderPermissions() },
            onTestSend = {
                vm.testSendToReceiver { _, detail ->
                    Toast.makeText(activity, detail, Toast.LENGTH_LONG).show()
                }
            },
            onSendDebugPing = { activity.sendDebugPing() },
            onCopyDebugLog = { activity.copyDebugLog() },
            onChangeRole = { vm.setRole(AppRole.NONE) }
        )

        AppRole.RECEIVER -> {
            LaunchedEffect(Unit) { vm.refreshPairingQr() }
            ReceiverScreen(
                state = state,
                messages = state.messages,
                hasNotificationPermission = activity.hasNotificationPermission(),
                onToggleReceive = { enabled ->
                    requestReceiverPermissions()
                    vm.setReceiveEnabled(activity, enabled)
                },
                onOpenAutofill = { activity.openAutofillSettings() },
                onClearInbox = vm::clearInbox,
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
                            "IP: $ip\nPort: ${com.smsforwarder.app.net.ReceiveServer.DEFAULT_PORT}\nSecret: ${state.sharedSecret}"
                        val cm = activity.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("lan-address", text))
                        Toast.makeText(activity, "Copied IP, port, secret", Toast.LENGTH_SHORT).show()
                    }
                },
                onSendDebugPing = { activity.sendDebugPing() },
                onCopyDebugLog = { activity.copyDebugLog() },
                onChangeRole = { vm.setRole(AppRole.NONE) }
            )
        }
    }
}
