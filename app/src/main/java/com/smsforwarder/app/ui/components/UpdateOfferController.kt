package com.smsforwarder.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    /** Incremented by drawer “Check for updates”; AppUpdateHost observes. */
    var manualCheckTick by mutableIntStateOf(0)
        private set

    val isForced: Boolean get() = available?.forceUpdate == true

    fun requestManualCheck() {
        manualCheckTick += 1
    }

    fun setOffer(update: AvailableUpdate) {
        available = update
        showPrompt = true
    }

    /** Session dismiss — drawer still shows the update. No-op when forced. */
    fun later() {
        if (available?.forceUpdate == true) return
        showPrompt = false
    }

    /** Permanent skip for this versionCode until a newer release. No-op when forced. */
    fun skipThisVersion(prefs: Prefs) {
        val update = available ?: return
        if (update.forceUpdate) return
        prefs.skippedUpdateVersionCode = update.versionCode
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
