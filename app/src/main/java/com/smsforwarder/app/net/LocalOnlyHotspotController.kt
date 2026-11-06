package com.smsforwarder.app.net

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * App-controlled local-only Soft AP (no internet share).
 * Fallback: open system Hotspot / tethering settings when the API fails.
 */
class LocalOnlyHotspotController(context: Context) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var reservation: WifiManager.LocalOnlyHotspotReservation? = null
        private set

    @Volatile
    var ssid: String = ""
        private set

    @Volatile
    var password: String = ""
        private set

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun start(onChanged: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            lastError = "Local hotspot needs Android 8+. Use system Hotspot settings."
            onChanged()
            return
        }
        if (reservation != null) {
            active = true
            onChanged()
            return
        }
        lastError = null
        try {
            startOreo(onChanged)
        } catch (e: SecurityException) {
            lastError = "Permission denied — open system Hotspot settings."
            onChanged()
        } catch (e: Exception) {
            lastError = e.message?.take(120) ?: "Could not start hotspot"
            onChanged()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startOreo(onChanged: () -> Unit) {
        wifi.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    this@LocalOnlyHotspotController.reservation = reservation
                    active = true
                    lastError = null
                    @Suppress("DEPRECATION")
                    val conf = reservation.wifiConfiguration
                    ssid = conf?.SSID?.trim('"').orEmpty()
                    password = conf?.preSharedKey?.trim('"').orEmpty()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            val soft = reservation.softApConfiguration
                            val softSsid = soft.ssid?.trim('"').orEmpty()
                            val softPass = soft.passphrase.orEmpty()
                            if (softSsid.isNotBlank()) ssid = softSsid
                            if (softPass.isNotBlank()) password = softPass
                        }
                    }
                    onChanged()
                }

                override fun onStopped() {
                    clearState()
                    onChanged()
                }

                override fun onFailed(reason: Int) {
                    clearState()
                    lastError = when (reason) {
                        ERROR_NO_CHANNEL -> "No Wi‑Fi channel available"
                        ERROR_GENERIC -> "Hotspot failed — try system Hotspot settings"
                        ERROR_INCOMPATIBLE_MODE -> "Wi‑Fi busy — turn off Wi‑Fi/hotspot and retry"
                        4 -> "Tethering disallowed on this device"
                        else -> "Hotspot failed (code $reason)"
                    }
                    onChanged()
                }
            },
            mainHandler
        )
    }

    fun stop(onChanged: () -> Unit = {}) {
        runCatching { reservation?.close() }
        clearState()
        onChanged()
    }

    private fun clearState() {
        reservation = null
        active = false
        ssid = ""
        password = ""
    }

    fun openSystemHotspotSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIFI_SETTINGS)
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
    }
}
