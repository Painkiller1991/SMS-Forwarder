package com.smsforwarder.app.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.smsforwarder.app.R
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.data.ListenFlag
import com.smsforwarder.app.net.ReceiveForegroundService
import com.smsforwarder.app.net.SmsForwardService
import com.smsforwarder.app.ui.MainActivity
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings tile:
 * - Receiver role: tap toggles Receive ON/OFF
 * - Sender role: shows pending sync count; tap drains outbox / opens app
 */
@RequiresApi(Build.VERSION_CODES.N)
class ReceiveTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val app = application as SmsForwarderApp
        val prefs = app.prefs
        when (prefs.role) {
            AppRole.RECEIVER -> {
                val next = !ListenFlag.read(this)
                prefs.receiveEnabled = next
                if (next) {
                    ReceiveForegroundService.start(this)
                } else {
                    ReceiveForegroundService.stop(this)
                }
                refresh()
            }
            AppRole.SENDER -> {
                val pending = runBlocking { app.database.outboxDao().pendingCount() }
                if (pending > 0) {
                    SmsForwardService.drain(this)
                    refresh()
                } else {
                    openApp()
                }
            }
            else -> openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val app = application as SmsForwarderApp
        val prefs = app.prefs
        when (prefs.role) {
            AppRole.RECEIVER -> {
                val on = ListenFlag.read(this)
                tile.label = if (on) "Receive ON" else "Receive OFF"
                tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "LAN listen"
                }
            }
            AppRole.SENDER -> {
                val pending = runBlocking { app.database.outboxDao().pendingCount() }
                tile.label = if (pending > 0) "Pending $pending" else "Sync"
                tile.state = if (pending > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Outbox"
                }
            }
            else -> {
                tile.label = "SMS Forwarder"
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Open app"
                }
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.updateTile()
    }

    companion object {
        fun requestListening(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, ReceiveTileService::class.java)
                )
            }
        }
    }
}
