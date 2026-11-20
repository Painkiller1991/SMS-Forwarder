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

        UpdateLock.clearIfSatisfied(prefs)
        if (UpdateLock.isActive(prefs)) {
            IdleRebindScheduler.cancel(context)
            return
        }
        if (prefs.role != AppRole.RECEIVER) {
            IdleRebindScheduler.cancel(context)
            return
        }

        // Migrate flag if needed, then trust ListenFlag (same source as :recv).
        val listenOn = prefs.receiveEnabled
        if (!listenOn && !ListenFlag.read(context)) {
            IdleRebindScheduler.cancel(context)
            return
        }
        if (!ListenFlag.read(context) && listenOn) {
            ListenFlag.write(context, true)
        }
        if (!ListenFlag.read(context)) {
            IdleRebindScheduler.cancel(context)
            return
        }

        val now = System.currentTimeMillis()
        val lastInbound = LastInboundStore.lastAt(context)
        val inboundAnchor = when {
            lastInbound == LastInboundStore.CORRUPT -> {
                // Corrupt store — force a rebind rather than resetting the clock to now.
                now - IdleRebindScheduler.INTERVAL_MS
            }
            lastInbound > 0L -> lastInbound
            else -> LastInboundStore.seedIfMissing(context, now).let { seeded ->
                if (seeded == LastInboundStore.CORRUPT) now - IdleRebindScheduler.INTERVAL_MS
                else if (seeded > 0L) seeded
                else now
            }
        }
        val lastRebind = LastInboundStore.lastIdleRebindAt(context)
        val idleMs = now - inboundAnchor
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
            ReceiveForegroundService.forceRebind(context)
            // Mark only after requesting rebind so a failed path can retry next window.
            LastInboundStore.markIdleRebind(context, now)
            IdleRebindScheduler.scheduleNext(context)
        } else {
            // Early inexact wake: schedule remaining time, not a fresh full 12h.
            val remainIdle = (IdleRebindScheduler.INTERVAL_MS - idleMs).coerceAtLeast(60_000L)
            val remainRebind = if (sinceRebind == Long.MAX_VALUE) {
                IdleRebindScheduler.INTERVAL_MS
            } else {
                (IdleRebindScheduler.INTERVAL_MS - sinceRebind).coerceAtLeast(60_000L)
            }
            IdleRebindScheduler.scheduleIn(
                context,
                minOf(remainIdle, remainRebind).coerceAtMost(IdleRebindScheduler.INTERVAL_MS)
            )
        }
    }
}
