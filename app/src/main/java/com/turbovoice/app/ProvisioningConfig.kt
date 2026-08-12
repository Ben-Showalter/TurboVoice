package com.turbovoice.app

/** Set a default trusted provisioning number here before building, so a
 *  freshly installed device doesn't need it entered manually in the app.
 *  Leave blank to require entering it on-device instead. Same trust
 *  model as TurboText's own ProvisioningConfig — anyone who can see this
 *  value has authority to remotely set the Groq API key on this device.
 *
 *  Example: const val DEFAULT_TRUSTED_NUMBER = "2695551234"
 */
object ProvisioningConfig {
    const val DEFAULT_TRUSTED_NUMBER = ""
}
