package com.smsforwarder.app.autofill

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.otp.StoredOtp

class SmsOtpAutofillService : AutofillService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }

        val ids = mutableListOf<AutofillId>()
        collectOtpFields(structure, ids)
        if (ids.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val store = (application as SmsForwarderApp).otpStore
        val immediate = store.getValid()
        if (immediate != null) {
            deliver(ids, immediate, callback)
            return
        }

        // User often focuses the OTP field before the SMS arrives — wait briefly.
        val deadline = System.currentTimeMillis() + WAIT_MS
        val poll = object : Runnable {
            override fun run() {
                if (cancellationSignal.isCanceled) {
                    runCatching { callback.onSuccess(null) }
                    return
                }
                val otp = store.getValid()
                if (otp != null) {
                    deliver(ids, otp, callback)
                    return
                }
                if (System.currentTimeMillis() >= deadline) {
                    callback.onSuccess(null)
                    return
                }
                mainHandler.postDelayed(this, POLL_MS)
            }
        }
        cancellationSignal.setOnCancelListener {
            mainHandler.removeCallbacks(poll)
        }
        mainHandler.postDelayed(poll, POLL_MS)
    }

    private fun deliver(
        ids: List<AutofillId>,
        otp: StoredOtp,
        callback: FillCallback
    ) {
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "OTP ${otp.code} (from ${otp.from})")
        }

        val datasetBuilder = Dataset.Builder()
        ids.forEach { id ->
            @Suppress("DEPRECATION")
            datasetBuilder.setValue(id, AutofillValue.forText(otp.code), presentation)
        }

        val response = FillResponse.Builder()
            .addDataset(datasetBuilder.build())
            .build()
        callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    private fun collectOtpFields(structure: AssistStructure, out: MutableList<AutofillId>) {
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode, out)
        }
    }

    private fun walk(node: AssistStructure.ViewNode, out: MutableList<AutofillId>) {
        val id = node.autofillId
        if (id != null && looksLikeOtpField(node)) {
            out.add(id)
        }
        for (i in 0 until node.childCount) {
            walk(node.getChildAt(i), out)
        }
    }

    private fun looksLikeOtpField(node: AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints?.joinToString(" ")?.lowercase().orEmpty()
        val hint = node.hint?.lowercase().orEmpty()
        val idEntry = node.idEntry?.lowercase().orEmpty()
        val className = node.className?.lowercase().orEmpty()
        if (!className.contains("edit")) return false

        val blob = "$hints $hint $idEntry"
        val keywords = listOf(
            "otp", "sms", "code", "pin", "verification", "verify",
            "one-time", "onetime", "passcode", "2fa", "mfa", "token",
            "کد", "تایید", "تائید"
        )
        if (keywords.any { blob.contains(it) }) return true

        // Many banking screens use a short numeric field without clear hints.
        val maxLength = node.maxTextLength
        return maxLength in 4..8
    }

    companion object {
        private const val WAIT_MS = 4_000L
        private const val POLL_MS = 200L
    }
}
