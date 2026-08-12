package com.turbovoice.app

import android.content.Context

object ProvisioningSettings {
    private const val PREFS = "turbovoice_settings"
    private const val KEY_TRUSTED_NUMBER = "trusted_provisioning_number"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getTrustedNumber(context: Context): String? {
        val manual = prefs(context).getString(KEY_TRUSTED_NUMBER, null)
        if (!manual.isNullOrBlank()) return manual
        return ProvisioningConfig.DEFAULT_TRUSTED_NUMBER.takeIf { it.isNotBlank() }
    }

    fun setTrustedNumber(context: Context, number: String?) {
        prefs(context).edit().putString(KEY_TRUSTED_NUMBER, number).apply()
    }
}
