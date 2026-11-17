package dev.blocks.android.follow

import android.content.Context

/** Non-secret user preference; automatic NFC Follow is opt-in. */
internal class AndroidFollowAutoSettings(context: Context) : FollowAutoSettings {
    private val preferences = context.applicationContext.getSharedPreferences(
        "blocks_follow_settings", Context.MODE_PRIVATE,
    )

    override fun enabled(): Boolean = preferences.getBoolean("automatic_nfc_follow", false)

    override fun setEnabled(value: Boolean) {
        check(preferences.edit().putBoolean("automatic_nfc_follow", value).commit()) {
            "Could not save automatic NFC Follow setting"
        }
    }
}
