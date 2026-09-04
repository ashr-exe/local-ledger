package dev.localledger.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.localledger.LocalLedgerApp

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val timestamp = messages.minOf { it.timestampMillis }
        val pending = goAsync()
        val repository = (context.applicationContext as LocalLedgerApp).repository
        repository.backgroundExecutor.execute {
            try {
                repository.ingestSms(sender, body, timestamp, System.currentTimeMillis())
            } finally {
                pending.finish()
            }
        }
    }
}
