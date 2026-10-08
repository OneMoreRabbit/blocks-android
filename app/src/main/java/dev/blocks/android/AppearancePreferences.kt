package dev.blocks.android

import android.content.Context
import android.content.res.Configuration

internal enum class AppearanceMode(val label: String) {
    FOLLOW_PHONE("Follow phone"),
    LIGHT("Light"),
    DARK("Dark"),
}

internal class AppearancePreferences(private val context: Context) {
    fun read(): AppearanceMode = runCatching {
        AppearanceMode.valueOf(store.getString(KEY_MODE, null) ?: AppearanceMode.FOLLOW_PHONE.name)
    }.getOrDefault(AppearanceMode.FOLLOW_PHONE)

    fun write(mode: AppearanceMode) {
        store.edit().putString(KEY_MODE, mode.name).apply()
    }

    private val store get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    companion object {
        private const val PREFERENCES = "blocks_device_appearance"
        private const val KEY_MODE = "mode"

        fun themedContext(base: Context): Context {
            val mode = AppearancePreferences(base).read()
            if (mode == AppearanceMode.FOLLOW_PHONE) return base
            val configuration = Configuration(base.resources.configuration)
            configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
                if (mode == AppearanceMode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            return base.createConfigurationContext(configuration)
        }
    }
}
