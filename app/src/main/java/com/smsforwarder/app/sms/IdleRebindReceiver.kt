package com.smsforwarder.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.data.AppRole
import com.smsforwarder.app.data.LastInboundStore
import com.smsforwarder.app.data.ListenFlag
import com.smsforwarder.app.debug.AgentDebugLog
import com.smsforwarder.app.net.IdleRebindScheduler
import com.smsforwarder.app.net.ReceiveForegroundService
import com.smsforwarder.app.update.UpdateLock

/** Every ~12h: if receive is ON and no inbound SMS for 12h, force-rebind listen. */
class IdleRebindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? SmsForwarderApp ?: return
        val prefs = app.prefs

        if (UpdateLock.isActive(prefs)) {
            // Engage already cancelled; do not reschedule under lock.
            return
        }
        if (prefs.role != AppRole.RECEIVER) {
            IdleRebindScheduler.cancel(context)
            return
        }

        // Either flag may lag across processes — keep the chain alive if either says ON.
        val listenOn = ListenFlag.read(context) || prefs.receiveEnabled
        if (!listenOn) {
            IdleRebindScheduler.cancel(context)
            return
        }

        val now = System.currentTimeMillis()
        val lastInbound = LastInboundStore.lastAt(context).takeIf { it > 0L } ?: now
        val lastRebind = LastInboundStore.lastIdleRebindAt(context)
        val idleMs = now - lastInbound
        val sinceRebind = if (lastRebind > 0L) now - lastRebind else Long.MAX_VALUE
        val shouldRebind =
            idleMs >= IdleRebindScheduler.INTERVAL_MS &&
                sinceRebind >= IdleRebindScheduler.INTERVAL_MS

        AgentDebugLog.log(
            hypothesisId = "IDLE12",
            location = "IdleRebindReceiver",
            message = if (shouldRebind) "Idle 12h — force rebind" else "Idle check — skip rebind",
            data = mapOf(
                "idleHours" to (idleMs / 3_600_000L),
                "sinceRebindHours" to (if (sinceRebind == Long.MAX_VALUE) -1 else sinceRebind / 3_600_000L),
                "shouldRebind" to shouldRebind
            ),
            runId = "idle-rebind"
        )

        if (shouldRebind) {
            LastInboundStore.markIdleRebind(context, now)
            ReceiveForegroundService.forceRebind(context)
        }

        // Always advance the next 12h window after a tick.
        IdleRebindScheduler.scheduleNext(context)
    }
}
