package com.smsforwarder.app.update

import android.content.Context
import com.smsforwarder.app.BuildConfig
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.ListenFlag
import com.smsforwarder.app.data.Prefs
import com.smsforwarder.app.net.KeepAliveScheduler
import com.smsforwarder.app.net.ReceiveForegroundService
import com.smsforwarder.app.net.SenderKeepAliveService

/**
 * Forced-update lock: when GitHub marks a release with forceUpdate,
 * features stay off until the installed versionCode catches up.
 */
object UpdateLock {
    fun isActive(prefs: Prefs): Boolean {
        val locked = prefs.updateLockedVersionCode
        return locked > 0 && BuildConfig.VERSION_CODE < locked
    }

    /** Clear stale lock after a successful upgrade. */
    fun clearIfSatisfied(prefs: Prefs) {
        val locked = prefs.updateLockedVersionCode
        if (locked > 0 && BuildConfig.VERSION_CODE >= locked) {
            prefs.updateLockedVersionCode = 0
        }
    }

    fun engage(context: Context, prefs: Prefs, targetVersionCode: Int) {
        prefs.updateLockedVersionCode = targetVersionCode
        prefs.receiveEnabled = false
        prefs.senderEnabled = false
        ListenFlag.write(context, false)
        KeepAliveScheduler.cancel(context)
        runCatching { ReceiveForegroundService.stop(context) }
        runCatching { SenderKeepAliveService.stop(context) }
    }

    fun isActive(context: Context): Boolean {
        val app = context.applicationContext as? SmsForwarderApp ?: return false
        clearIfSatisfied(app.prefs)
        return isActive(app.prefs)
    }
}
