package com.smsforwarder.app.tile

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import com.smsforwarder.app.R

/**
 * Helpers for the Quick Settings tile (shade tile — not a home-screen widget).
 */
object QsTileHelper {
    fun component(context: Context): ComponentName =
        ComponentName(context, ReceiveTileService::class.java)

    /**
     * Asks Android to show the system “Add tile?” dialog (API 33+).
     * Returns a short user-facing message describing what happened / next step.
     */
    fun requestAdd(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return MANUAL_STEPS
        }
        val sbm = context.getSystemService(StatusBarManager::class.java)
            ?: return "Could not open tile settings. $MANUAL_STEPS"
        val appCtx = context.applicationContext
        return try {
            sbm.requestAddTileService(
                component(context),
                context.getString(R.string.qs_tile_label),
                Icon.createWithResource(context, R.drawable.ic_notification),
                context.mainExecutor
            ) { result ->
                val msg = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        "Tile already added — pull down the status bar to find “SMS Forwarder”."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                        "Tile added. Pull down the status bar to use it."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                        "Tile not added. $MANUAL_STEPS"
                    else ->
                        "No tile added. $MANUAL_STEPS"
                }
                Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
            }
            "Look for the system “Add SMS Forwarder?” prompt. If nothing appears, add it manually from Quick Settings edit."
        } catch (e: Exception) {
            "Could not request tile (${e.message}). $MANUAL_STEPS"
        }
    }

    fun showAddToast(context: Context) {
        Toast.makeText(context, requestAdd(context), Toast.LENGTH_LONG).show()
    }

    const val MANUAL_STEPS =
        "Pull down the status bar twice → tap the pencil/Edit → find “SMS Forwarder” → drag it into your tiles."
}
