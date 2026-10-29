package com.smsforwarder.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.smsforwarder.app.otp.OtpParser

class CopyOtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        var otp = intent.getStringExtra(EXTRA_OTP).orEmpty().trim()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        if (otp.isBlank() && body.isNotBlank()) {
            otp = OtpParser.extract(body).orEmpty()
        }
        if (otp.isBlank()) {
            if (!intent.getBooleanExtra(InboxNotifier.EXTRA_SILENT, false)) {
                Toast.makeText(context, "No OTP found", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val ok = InboxNotifier.copyOtp(context, otp)
        val silent = intent.getBooleanExtra(InboxNotifier.EXTRA_SILENT, false)
        if (!silent) {
            Toast.makeText(
                context,
                if (ok) "OTP copied" else "Could not copy OTP",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        const val EXTRA_OTP = "otp"
        const val EXTRA_BODY = "body"
    }
}
