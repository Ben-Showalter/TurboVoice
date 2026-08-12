package com.turbovoice.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Reacts to incoming texts to catch a specially-formatted provisioning
 *  message (see ApiKeyProvisioningHelper). Uses the plain RECEIVE_SMS
 *  broadcast rather than the default-SMS-app-only SMS_DELIVER intent, so
 *  this works without TurboVoice taking over as the device's messaging
 *  app — it just gets a look at each incoming message alongside whatever
 *  app (e.g. TurboText) actually handles SMS here. */
class ProvisioningSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val sender = messages[0].originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }
        ApiKeyProvisioningHelper.tryHandleProvisioningMessage(context, sender, body)
    }
}
