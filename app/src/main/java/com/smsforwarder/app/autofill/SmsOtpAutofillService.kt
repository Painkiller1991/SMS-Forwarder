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
import android.text.InputType
import android.view.View
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

        val store = (application as SmsForwarderApp).otpStore
        val immediate = store.getValid()
        val hasOtp = immediate != null

        val ids = mutableListOf<AutofillId>()
        collectOtpFields(structure, ids, aggressive = hasOtp)
        if (ids.isEmpty()) {
            waitForOtpAndDeliver(structure, cancellationSignal, callback, requireFieldsFirst = false)
            return
        }

        if (immediate != null) {
            deliver(ids, immediate, callback)
            return
        }

        waitForOtpAndDeliver(structure, cancellationSignal, callback, requireFieldsFirst = true)
    }

    private fun waitForOtpAndDeliver(
        structure: AssistStructure,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
        requireFieldsFirst: Boolean
    ) {
        val store = (application as SmsForwarderApp).otpStore
        val deadline = System.currentTimeMillis() + WAIT_MS
        val poll = object : Runnable {
            override fun run() {
                if (cancellationSignal.isCanceled) {
                    runCatching { callback.onSuccess(null) }
                    return
                }
                val otp = store.getValid()
                if (otp != null) {
                    val ids = mutableListOf<AutofillId>()
                    collectOtpFields(structure, ids, aggressive = true)
                    if (ids.isNotEmpty()) {
                        deliver(ids, otp, callback)
                        return
                    }
                    if (!requireFieldsFirst) {
                        callback.onSuccess(null)
                        return
                    }
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
            setTextViewText(android.R.id.text1, "OTP ${otp.code}")
        }

        val datasetBuilder = Dataset.Builder()
        ids.forEach { id ->
            @Suppress("DEPRECATION")
            datasetBuilder.setValue(id, AutofillValue.forText(otp.code), presentation)
        }

        callback.onSuccess(
            FillResponse.Builder()
                .addDataset(datasetBuilder.build())
                .build()
        )
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    private fun collectOtpFields(
        structure: AssistStructure,
        out: MutableList<AutofillId>,
        aggressive: Boolean
    ) {
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode, out, aggressive)
        }
    }

    private fun walk(
        node: AssistStructure.ViewNode,
        out: MutableList<AutofillId>,
        aggressive: Boolean
    ) {
        val id = node.autofillId
        if (id != null && looksLikeOtpField(node, aggressive)) {
            out.add(id)
        }
        for (i in 0 until node.childCount) {
            walk(node.getChildAt(i), out, aggressive)
        }
    }

    private fun looksLikeOtpField(node: AssistStructure.ViewNode, aggressive: Boolean): Boolean {
        if (!isEditable(node)) return false

        val hints = node.autofillHints?.joinToString(" ")?.lowercase().orEmpty()
        val hint = node.hint?.lowercase().orEmpty()
        val idEntry = node.idEntry?.lowercase().orEmpty()
        val blob = "$hints $hint $idEntry"

        if (hints.contains("smsotp") ||
            hints.contains("sms_otp") ||
            hints.contains("sms otp") ||
            hints.contains("onetimecode") ||
            hints.contains("one_time_code") ||
            hints.contains("onetime code")
        ) {
            return true
        }

        val keywords = listOf(
            "otp", "sms", "code", "pin", "verification", "verify",
            "one-time", "onetime", "passcode", "2fa", "mfa", "token",
            "کد", "تایید", "تائید", "ورود"
        )
        if (keywords.any { blob.contains(it) }) return true

        val maxLength = node.maxTextLength
        if (maxLength in 4..8) return true

        val inputType = node.inputType
        val clazz = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isNumber = clazz == InputType.TYPE_CLASS_NUMBER
        val isNumberPassword = isNumber &&
            (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val isTextPassword = clazz == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)

        if (isNumberPassword || (isNumber && maxLength in 1..10)) return true
        if (isTextPassword && maxLength in 4..8) return true

        if (aggressive && node.isFocused) {
            if (isNumber || isTextPassword) return true
            if (maxLength in 1..10 || maxLength <= 0) return true
        }
        return false
    }

    private fun isEditable(node: AssistStructure.ViewNode): Boolean {
        val className = node.className?.lowercase().orEmpty()
        if (className.contains("edit") || className.contains("textfield") ||
            className.contains("autocompletetext")
        ) {
            return true
        }
        return node.autofillType == View.AUTOFILL_TYPE_TEXT
    }

    companion object {
        /** SMS can lag behind field focus on LAN forward. */
        private const val WAIT_MS = 15_000L
        private const val POLL_MS = 250L
    }
}
