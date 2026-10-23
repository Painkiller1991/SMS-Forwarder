package com.smsforwarder.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.smsforwarder.app.data.Prefs
import com.smsforwarder.app.update.AvailableUpdate

class UpdateOfferController {
    var available by mutableStateOf<AvailableUpdate?>(null)
        private set
    var showPrompt by mutableStateOf(false)
        private set

    fun setOffer(update: AvailableUpdate) {
        available = update
        showPrompt = true
    }

    /** Session dismiss — drawer still shows the update. */
    fun later() {
        showPrompt = false
    }

    /** Permanent skip for this versionCode until a newer release. */
    fun skipThisVersion(prefs: Prefs) {
        val code = available?.versionCode ?: return
        prefs.skippedUpdateVersionCode = code
        available = null
        showPrompt = false
    }

    fun openFromDrawer() {
        if (available != null) showPrompt = true
    }

    fun clearAfterInstall() {
        available = null
        showPrompt = false
    }
}

val LocalUpdateOffer = compositionLocalOf<UpdateOfferController?> { null }

@Composable
fun rememberUpdateOfferController(): UpdateOfferController = remember { UpdateOfferController() }
