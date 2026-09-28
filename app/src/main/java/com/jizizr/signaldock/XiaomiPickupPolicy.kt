package com.jizizr.signaldock

/** Memory fetches use a child dialog; arbitrary pushes must never receive the current image. */
internal fun isPickupDialog(requestId: String, dialogId: String): Boolean {
    if (requestId.isBlank()) return false
    if (dialogId == requestId) return true
    val prefix = "$requestId-MemoryPush-"
    if (!dialogId.startsWith(prefix)) return false
    val suffix = dialogId.removePrefix(prefix)
    return suffix.isNotEmpty() && suffix.all { it in '0'..'9' }
}

internal fun isXiaomiConfigurationReady(
    pickup: Boolean,
    mode: XiaoAiMode,
    connected: Boolean,
    independentSession: Boolean,
    expertConnected: Boolean,
): Boolean = connected && if (pickup) independentSession else mode == XiaoAiMode.FAST || expertConnected
