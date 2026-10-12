package com.gamch1k.lifeos.wear.update

import android.content.Context
import com.gamch1k.lifeos.core.wearlink.RelayCodec
import com.gamch1k.lifeos.core.wearlink.WatchUpdateResult
import com.gamch1k.lifeos.core.wearlink.WatchUpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * What the watch remembers about its own updates, across the restart an
 * update causes: the last result (reported to the phone once), when it last
 * committed an install (Android's 30-second rule), and the version it is
 * installing.
 */
class WatchUpdateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableResult = MutableStateFlow(readResult())

    /** The last install the watch tried, and how it ended. */
    val result: StateFlow<WatchUpdateResult?> = mutableResult.asStateFlow()

    val lastInstallAt: Instant?
        get() = preferences.getString(KEY_LAST_INSTALL_AT, null)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** The versionCode being installed (or waiting for confirmation), or 0. */
    val pendingVersionCode: Long get() = preferences.getLong(KEY_PENDING_VERSION, 0)

    val reported: Boolean get() = preferences.getBoolean(KEY_REPORTED, true)

    /** A commit to PackageInstaller is about to happen. */
    fun installing(versionCode: Long, versionName: String, at: Instant) {
        preferences.edit()
            .putString(KEY_LAST_INSTALL_AT, at.toString())
            .putLong(KEY_PENDING_VERSION, versionCode)
            .commit()
        save(WatchUpdateResult(versionCode, versionName, WatchUpdateStatus.INSTALLING, "", at.toString()), pending = true)
    }

    /** Records [result]; [pending] keeps the pending version (the install isn't over). The phone hears of it next. */
    fun save(result: WatchUpdateResult, pending: Boolean = false) {
        val editor = preferences.edit()
            .putString(KEY_RESULT, RelayCodec.json.encodeToString(WatchUpdateResult.serializer(), result))
            .putBoolean(KEY_REPORTED, false)
        if (!pending) editor.remove(KEY_PENDING_VERSION)
        // Written at once: a successful install ends this process right after the commit.
        editor.commit()
        mutableResult.value = result
    }

    fun markReported(result: WatchUpdateResult) {
        if (mutableResult.value == result) preferences.edit().putBoolean(KEY_REPORTED, true).apply()
    }

    private fun readResult(): WatchUpdateResult? = preferences.getString(KEY_RESULT, null)?.let {
        runCatching { RelayCodec.json.decodeFromString(WatchUpdateResult.serializer(), it) }.getOrNull()
    }

    private companion object {
        const val PREFERENCES = "wear-updates"
        const val KEY_RESULT = "last-result"
        const val KEY_REPORTED = "last-result-reported"
        const val KEY_LAST_INSTALL_AT = "last-install-at"
        const val KEY_PENDING_VERSION = "pending-version-code"
    }
}
