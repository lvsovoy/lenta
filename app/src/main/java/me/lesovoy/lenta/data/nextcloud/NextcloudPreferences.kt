package me.lesovoy.lenta.data.nextcloud

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

open class NextcloudPreferences(
    private val prefs: SharedPreferences? = null
) {
    constructor(context: Context) : this(PreferenceManager.getDefaultSharedPreferences(context))

    private var memoryServerUrl: String = ""
    private var memoryUsername: String = ""
    private var memoryPassword: String = ""
    private var memoryMuteByDefault: Boolean = true
    private var memoryLoopVideo: Boolean = true
    private var memoryShowHiddenFiles: Boolean = false
    private var memoryCheckUpdatesStartup: Boolean = true
    private var memoryLastUpdateCheckTimestamp: Long = 0L

    companion object {
        private const val KEY_SERVER_URL = "nc_server_url"
        private const val KEY_USERNAME = "nc_username"
        private const val KEY_PASSWORD = "nc_password"
        private const val KEY_MUTE_DEFAULT = "pref_mute_default"
        private const val KEY_LOOP_VIDEO = "pref_loop_video"
        private const val KEY_SHOW_HIDDEN_FILES = "pref_show_hidden_files"
        private const val KEY_CHECK_UPDATES_STARTUP = "pref_check_updates_startup"
        private const val KEY_LAST_UPDATE_CHECK = "pref_last_update_check"
    }

    var serverUrl: String
        get() = prefs?.getString(KEY_SERVER_URL, "") ?: memoryServerUrl
        set(value) {
            val trimmed = value.trim()
            memoryServerUrl = trimmed
            prefs?.edit()?.putString(KEY_SERVER_URL, trimmed)?.apply()
        }

    var username: String
        get() = prefs?.getString(KEY_USERNAME, "") ?: memoryUsername
        set(value) {
            val trimmed = value.trim()
            memoryUsername = trimmed
            prefs?.edit()?.putString(KEY_USERNAME, trimmed)?.apply()
        }

    var password: String
        get() = prefs?.getString(KEY_PASSWORD, "") ?: memoryPassword
        set(value) {
            memoryPassword = value
            prefs?.edit()?.putString(KEY_PASSWORD, value)?.apply()
        }

    var muteByDefault: Boolean
        get() = prefs?.getBoolean(KEY_MUTE_DEFAULT, true) ?: memoryMuteByDefault
        set(value) {
            memoryMuteByDefault = value
            prefs?.edit()?.putBoolean(KEY_MUTE_DEFAULT, value)?.apply()
        }

    var loopVideo: Boolean
        get() = prefs?.getBoolean(KEY_LOOP_VIDEO, true) ?: memoryLoopVideo
        set(value) {
            memoryLoopVideo = value
            prefs?.edit()?.putBoolean(KEY_LOOP_VIDEO, value)?.apply()
        }

    var showHiddenFiles: Boolean
        get() = prefs?.getBoolean(KEY_SHOW_HIDDEN_FILES, false) ?: memoryShowHiddenFiles
        set(value) {
            memoryShowHiddenFiles = value
            prefs?.edit()?.putBoolean(KEY_SHOW_HIDDEN_FILES, value)?.apply()
        }

    var checkUpdatesOnStartup: Boolean
        get() = prefs?.getBoolean(KEY_CHECK_UPDATES_STARTUP, true) ?: memoryCheckUpdatesStartup
        set(value) {
            memoryCheckUpdatesStartup = value
            prefs?.edit()?.putBoolean(KEY_CHECK_UPDATES_STARTUP, value)?.apply()
        }

    var lastUpdateCheckTimestamp: Long
        get() = prefs?.getLong(KEY_LAST_UPDATE_CHECK, 0L) ?: memoryLastUpdateCheckTimestamp
        set(value) {
            memoryLastUpdateCheckTimestamp = value
            prefs?.edit()?.putLong(KEY_LAST_UPDATE_CHECK, value)?.apply()
        }

    fun isConfigured(): Boolean {
        return serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    }

    fun clear() {
        memoryServerUrl = ""
        memoryUsername = ""
        memoryPassword = ""
        prefs?.edit()
            ?.remove(KEY_SERVER_URL)
            ?.remove(KEY_USERNAME)
            ?.remove(KEY_PASSWORD)
            ?.apply()
    }
}
