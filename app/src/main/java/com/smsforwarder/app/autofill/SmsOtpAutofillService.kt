package com.smsforwarder.app.autofill

import android.app.assist.AssistStructure
import android.os.CancellationSignal
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

class SmsOtpAutofillService : AutofillService() {
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        // Reads file-backed store so OTP written in :recv is visible here
        val otp = (application as SmsForwarderApp).otpStore.getValid()
        if (otp == null) {
            callback.onSuccess(null)
            return
        }

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
}
