package com.turbovoice.app

import android.content.Context

/** The user's chosen trigger key override, if any — plain
 *  SharedPreferences, same approach as TurboLaunch's
 *  LauncherAppsStorage.getShortcutKeycode/setShortcutKeycode. Null means
 *  no override has been set, in which case the accessibility service
 *  falls back to this device's default Mic/Assistant button keycodes. */
object TriggerKeyStorage {
    private const val PREFS = "turbovoice_prefs"
    private const val KEY_TRIGGER_KEYCODE = "trigger_keycode"

    /** Null means no custom trigger key has been set yet. */
    fun getTriggerKeycode(context: Context): Int? {
        val v = prefs(context).getInt(KEY_TRIGGER_KEYCODE, -1)
        return if (v == -1) null else v
    }

    fun setTriggerKeycode(context: Context, keycode: Int) {
        prefs(context).edit().putInt(KEY_TRIGGER_KEYCODE, keycode).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
