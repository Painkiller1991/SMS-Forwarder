package com.smsforwarder.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.smsforwarder.app.BuildConfig
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.update.ApkInstaller
import com.smsforwarder.app.update.AvailableUpdate
import com.smsforwarder.app.update.UpdateCheckResult
import com.smsforwarder.app.update.UpdateChecker
import com.smsforwarder.app.update.UpdateDownloader
import com.smsforwarder.app.update.UpdateLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AppUpdateHost(
    activity: ComponentActivity,
    offer: UpdateOfferController,
    onForceLockApplied: () -> Unit = {}
) {
    val prefs = remember {
        (activity.application as SmsForwarderApp).prefs
    }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var pendingInstall by remember { mutableStateOf(false) }
    var lastAutoCheckAt by remember { mutableLongStateOf(0L) }

    fun applyCheckResult(result: UpdateCheckResult, fromManual: Boolean) {
        when (result) {
            is UpdateCheckResult.Available -> {
                val update = result.update
                if (update.forceUpdate) {
                    UpdateLock.engage(activity, prefs, update.versionCode)
                    onForceLockApplied()
                }
                offer.setOffer(update)
            }
            is UpdateCheckResult.UpToDate -> {
                if (fromManual) {
                    Toast.makeText(
                        activity,
                        "You're up to date (v${BuildConfig.VERSION_NAME})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            is UpdateCheckResult.Failed -> {
                if (fromManual) {
                    Toast.makeText(
                        activity,
                        "Update check failed — opening GitHub Latest",
                        Toast.LENGTH_LONG
                    ).show()
                    runCatching {
                        activity.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(UpdateChecker.LATEST_RELEASE_PAGE)
                            )
                        )
                    }
                }
            }
        }
    }

    fun runUpdateCheck(fromManual: Boolean) {
        scope.launch {
            UpdateLock.clearIfSatisfied(prefs)
            if (fromManual) {
                Toast.makeText(activity, "Checking…", Toast.LENGTH_SHORT).show()
            }
            val result = UpdateChecker.check(prefs.skippedUpdateVersionCode)
            lastAutoCheckAt = System.currentTimeMillis()
            applyCheckResult(result, fromManual)
        }
    }

    val installPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingInstall && ApkInstaller.canInstallPackages(activity)) {
            pendingInstall = false
            offer.available?.let { update ->
                startDownload(
                    activity = activity,
                    update = update,
                    scope = scope,
                    setDownloading = { downloading = it },
                    setProgress = { progress = it },
                    setJob = { downloadJob = it },
                    onDone = { offer.clearAfterInstall(); downloading = false }
                )
            }
        } else {
            pendingInstall = false
        }
    }

    // Cold start + whenever the UI returns to foreground (throttled).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val now = System.currentTimeMillis()
            if (now - lastAutoCheckAt < 30_000L) return@LifecycleEventObserver
            runUpdateCheck(fromManual = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(offer.manualCheckTick) {
        if (offer.manualCheckTick <= 0) return@LaunchedEffect
        runUpdateCheck(fromManual = true)
    }

    val update = offer.available
    val forced = update?.forceUpdate == true

    if (forced) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* block underlying UI */ }
        )
    }

    if (update != null && offer.showPrompt && !downloading) {
        UpdateAvailableDialog(
            update = update,
            onDownload = {
                if (!ApkInstaller.canInstallPackages(activity)) {
                    pendingInstall = true
                    Toast.makeText(
                        activity,
                        "Allow installing apps from this source, then try again",
                        Toast.LENGTH_LONG
                    ).show()
                    installPermLauncher.launch(ApkInstaller.installPermissionSettingsIntent(activity))
                    return@UpdateAvailableDialog
                }
                startDownload(
                    activity = activity,
                    update = update,
                    scope = scope,
                    setDownloading = { downloading = it },
                    setProgress = { progress = it },
                    setJob = { downloadJob = it },
                    onDone = { offer.clearAfterInstall(); downloading = false }
                )
            },
            onLater = { offer.later() },
            onSkip = { offer.skipThisVersion(prefs) }
        )
    }

    if (downloading) {
        UpdateProgressDialog(
            progress = progress,
            allowCancel = !forced,
            onCancel = {
                if (forced) return@UpdateProgressDialog
                downloadJob?.cancel()
                downloadJob = null
                downloading = false
            }
        )
    }
}

private fun startDownload(
    activity: ComponentActivity,
    update: AvailableUpdate,
    scope: kotlinx.coroutines.CoroutineScope,
    setDownloading: (Boolean) -> Unit,
    setProgress: (Float) -> Unit,
    setJob: (Job) -> Unit,
    onDone: () -> Unit
) {
    setDownloading(true)
    setProgress(0f)
    val job = scope.launch {
        val result = UpdateDownloader.download(activity, update.apkDownloadUrl) { p ->
            setProgress(p)
        }
        result.onSuccess { file ->
            runCatching { ApkInstaller.installApk(activity, file) }
                .onFailure {
                    Toast.makeText(
                        activity,
                        "Install failed: ${it.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            onDone()
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) {
                onDone()
            } else {
                Toast.makeText(
                    activity,
                    "Download failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                setDownloading(false)
            }
        }
    }
    setJob(job)
}
