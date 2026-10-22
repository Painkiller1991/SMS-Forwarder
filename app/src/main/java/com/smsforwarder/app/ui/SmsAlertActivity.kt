package com.smsforwarder.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.smsforwarder.app.R

/**
 * Full-screen heads-up when an SMS arrives while the phone is locked / app closed.
 * Finishes after a short delay or when the user taps.
 */
class SmsAlertActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val otp = intent.getStringExtra(EXTRA_OTP)

        val tv = TextView(this).apply {
            textSize = 20f
            setPadding(48, 96, 48, 48)
            text = buildString {
                append(getString(R.string.notification_title_sms, sender))
                append("\n\n")
                if (!otp.isNullOrBlank()) append("OTP: ").append(otp).append("\n\n")
                append(body)
                append("\n\n(tap to close)")
            }
            setOnClickListener { finish() }
        }
        setContentView(tv)
        tv.postDelayed({ if (!isFinishing) finish() }, 12_000)
    }

    companion object {
        const val EXTRA_SENDER = "sender"
        const val EXTRA_BODY = "body"
        const val EXTRA_OTP = "otp"
    }
}
