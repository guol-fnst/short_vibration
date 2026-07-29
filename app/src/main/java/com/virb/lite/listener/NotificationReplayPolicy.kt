package com.virb.lite.listener

internal fun shouldSuppressUnlockReplay(
    notificationPostTimeMs: Long,
    lastUserPresentAtMs: Long,
    nowMs: Long,
    suppressWindowMs: Long,
): Boolean {
    if (lastUserPresentAtMs <= 0L) return false
    if (notificationPostTimeMs > lastUserPresentAtMs) return false
    return nowMs - lastUserPresentAtMs in 0 until suppressWindowMs
}
