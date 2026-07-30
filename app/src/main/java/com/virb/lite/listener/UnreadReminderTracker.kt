package com.virb.lite.listener

internal class UnreadReminderTracker {
    private val notifications = LinkedHashMap<String, String>()

    var repeatCount: Int = 0
        private set

    val isEmpty: Boolean
        get() = notifications.isEmpty()

    val size: Int
        get() = notifications.size

    fun track(key: String, packageName: String) {
        notifications.remove(key)
        notifications[key] = packageName
        repeatCount = 0
    }

    fun remove(key: String): Boolean = notifications.remove(key) != null

    fun retainActive(activeKeys: Set<String>) {
        notifications.keys.retainAll(activeKeys)
    }

    fun latestPackage(): String? = notifications.values.lastOrNull()

    fun markRepeated() {
        repeatCount += 1
    }

    fun clear() {
        notifications.clear()
        repeatCount = 0
    }
}
