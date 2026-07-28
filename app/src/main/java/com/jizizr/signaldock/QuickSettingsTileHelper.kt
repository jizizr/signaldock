package com.jizizr.signaldock

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon

object QuickSettingsTileHelper {
    fun requestAdd(context: Context, onResult: (String) -> Unit) {
        val manager = context.getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            onResult(context.getString(R.string.tile_add_unavailable))
            return
        }

        manager.requestAddTileService(
            ComponentName(context, AnalysisTileService::class.java),
            context.getString(R.string.tile_label),
            Icon.createWithResource(context, R.drawable.ic_launcher_foreground),
            context.mainExecutor,
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                    R.string.tile_add_success
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    R.string.tile_add_already_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                    R.string.tile_add_not_added
                StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND ->
                    R.string.tile_add_app_not_foreground
                StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS ->
                    R.string.tile_add_request_in_progress
                else -> R.string.tile_add_failed
            }
            onResult(context.getString(message))
        }
    }
}
