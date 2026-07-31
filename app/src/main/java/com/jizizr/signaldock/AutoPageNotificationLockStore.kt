package com.jizizr.signaldock

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class AutoPageNotificationLock(
    val profileId: String,
    val sessionId: Int,
    val notificationId: Int,
    val createdAtMs: Long,
)

/**
 * Keeps an auto page disarmed for exactly as long as its result notification exists.
 *
 * The lock is persisted because the accessibility service can reconnect while the
 * notification is still active. The notification's action/delete intent releases it.
 */
object AutoPageNotificationLockStore {
    private const val PREFS_NAME = "auto_page_notification_locks"
    private const val KEY_PREFIX = "profile:"
    private const val MISSING_NOTIFICATION_GRACE_MS = 10_000L
    private lateinit var preferences: SharedPreferences
    @Volatile
    private var locksByProfile: Map<String, AutoPageNotificationLock> = emptyMap()

    fun init(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        locksByProfile = readStoredLocks().associateBy(AutoPageNotificationLock::profileId)
    }

    @Synchronized
    fun lock(profileId: String, sessionId: Int, notificationId: Int) {
        if (profileId.isBlank()) return
        val lock = AutoPageNotificationLock(
            profileId = profileId,
            sessionId = sessionId,
            notificationId = notificationId,
            createdAtMs = System.currentTimeMillis(),
        )
        locksByProfile = locksByProfile + (profileId to lock)
        preferences.edit {
            putString(
                key(profileId),
                listOf(lock.sessionId, lock.notificationId, lock.createdAtMs).joinToString("|"),
            )
        }
        AppLog.i(
            "AutoPageNotificationLock",
            "locked profile=$profileId session=$sessionId notification=$notificationId",
        )
    }

    @Synchronized
    fun isLocked(profileId: String): Boolean = profileId in locksByProfile

    @Synchronized
    fun releaseBySession(sessionId: Int): AutoPageNotificationLock? {
        val lock = locksByProfile.values.firstOrNull { it.sessionId == sessionId } ?: return null
        locksByProfile = locksByProfile - lock.profileId
        preferences.edit { remove(key(lock.profileId)) }
        AppLog.i(
            "AutoPageNotificationLock",
            "released profile=${lock.profileId} session=$sessionId notification=${lock.notificationId}",
        )
        return lock
    }

    @Synchronized
    fun releaseByProfile(profileId: String): AutoPageNotificationLock? {
        val lock = locksByProfile[profileId] ?: return null
        locksByProfile = locksByProfile - profileId
        preferences.edit { remove(key(profileId)) }
        return lock
    }

    /** Removes stale locks after a process/service restart if their notification is gone. */
    @Synchronized
    fun pruneMissingNotifications(context: Context): List<AutoPageNotificationLock> {
        val activeIds = context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .mapTo(hashSetOf()) { it.id }
        val now = System.currentTimeMillis()
        val stale = locksByProfile.values.filter { lock ->
            now - lock.createdAtMs >= MISSING_NOTIFICATION_GRACE_MS &&
                lock.notificationId !in activeIds
        }
        if (stale.isNotEmpty()) {
            locksByProfile = locksByProfile - stale.map(AutoPageNotificationLock::profileId).toSet()
            preferences.edit {
                stale.forEach { remove(key(it.profileId)) }
            }
            AppLog.i(
                "AutoPageNotificationLock",
                "pruned missing notifications profiles=${stale.joinToString { it.profileId }}",
            )
        }
        return stale
    }

    @Synchronized
    internal fun loadAll(): List<AutoPageNotificationLock> = locksByProfile.values.toList()

    private fun readStoredLocks(): List<AutoPageNotificationLock> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith(KEY_PREFIX) }
        .mapNotNull { storedKey -> read(storedKey.removePrefix(KEY_PREFIX)) }
        .toList()

    private fun read(profileId: String): AutoPageNotificationLock? {
        val parts = preferences.getString(key(profileId), null)?.split('|') ?: return null
        if (parts.size != 3) return null
        return AutoPageNotificationLock(
            profileId = profileId,
            sessionId = parts[0].toIntOrNull() ?: return null,
            notificationId = parts[1].toIntOrNull() ?: return null,
            createdAtMs = parts[2].toLongOrNull() ?: return null,
        )
    }

    private fun key(profileId: String): String = KEY_PREFIX + profileId
}
