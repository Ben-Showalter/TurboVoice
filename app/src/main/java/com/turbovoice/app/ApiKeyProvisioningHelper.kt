package com.turbovoice.app

import android.content.Context
import android.util.Base64
import android.util.Log
import android.widget.Toast

/** Lets the Groq API key be set remotely via a specially-formatted text
 *  message, writing straight into the same Turbo Key/groq_api_key.txt
 *  file the manual setup flow uses — mirrors TurboText's own
 *  ApiKeyProvisioningHelper, with one real difference: TurboText IS the
 *  default SMS app, so it can quietly swallow its provisioning texts
 *  before they ever reach the inbox. TurboVoice isn't (and shouldn't
 *  be — that's TurboText's job, and taking it over would break normal
 *  texting), so it only ever sees a *copy* of the message via the
 *  regular RECEIVE_SMS broadcast. It can react to it, but it can't stop
 *  it from also showing up as a normal (base64-gibberish) text in
 *  whatever app actually handles messaging on this device.
 *
 *  Deliberately restricted the same way TurboText's is: nothing happens
 *  unless a trusted number has already been set locally first, and even
 *  then, only a message from THAT exact number, in the exact expected
 *  format, is treated as a provisioning command.
 *
 *  The key is base64-encoded in the message, not encrypted — light
 *  obfuscation (so it's not obviously-a-key plain text sitting in the
 *  SMS history), not real security. SMS itself isn't a secure transport. */
object ApiKeyProvisioningHelper {
    private const val TAG = "TurboVoiceProvisioning"
    private const val PREFIX = "TURBOVOICE_SETUP:"

    fun tryHandleProvisioningMessage(context: Context, sender: String, body: String) {
        val trimmedBody = body.trim()
        if (!trimmedBody.startsWith(PREFIX)) return

        val trustedNumber = ProvisioningSettings.getTrustedNumber(context)
        if (trustedNumber.isNullOrBlank()) {
            Log.w(TAG, "provisioning-formatted message received but no trusted number is configured — ignoring")
            return
        }
        if (!numbersMatch(sender, trustedNumber)) {
            Log.w(TAG, "provisioning-formatted message received from an untrusted number — ignoring")
            return
        }

        val encoded = trimmedBody.removePrefix(PREFIX).trim()
        val decoded = try {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.w(TAG, "provisioning message from the trusted number, but couldn't decode the key", e)
            return
        }
        if (decoded.isEmpty()) return

        try {
            GroqConfig.ensureKeyFileExists()
            GroqConfig.keyFile().writeText(decoded)
            Log.i(TAG, "API key updated via remote provisioning")
            Toast.makeText(context, "TurboVoice: API key updated via text message", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "decoded key but couldn't write it to the key file (storage permission missing?)", e)
        }
    }

    /** Loose match — incoming sender numbers can be formatted many ways
     *  (+1 prefix, dashes, parens) compared to however the trusted
     *  number was typed in. */
    private fun numbersMatch(a: String, b: String): Boolean {
        fun digitsOnly(s: String) = s.filter { it.isDigit() }.takeLast(10)
        val da = digitsOnly(a)
        val db = digitsOnly(b)
        return da.isNotEmpty() && da == db
    }
}
