package com.smsforwarder.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CopyOtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val otp = intent.getStringExtra(EXTRA_OTP) ?: return
        InboxNotifier.copyOtp(context, otp)
        Toast.makeText(context, "OTP copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_OTP = "otp"
    }
}
