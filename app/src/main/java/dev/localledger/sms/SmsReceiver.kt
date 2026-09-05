package dev.localledger.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.localledger.LocalLedgerApp

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val repository = (context.applicationContext as LocalLedgerApp).repository
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) {
            repository.recordReceiverIssue("RECEIVER_EMPTY", "SMS_RECEIVED contained no decoded messages")
            return
        }

        val sender = messages.first().originatingAddress
        if (sender == null) {
            repository.recordReceiverIssue("RECEIVER_NO_SENDER", "decodedParts=" + messages.size)
            return
        }
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val timestamp = messages.minOf { it.timestampMillis }
        val pending = goAsync()
        repository.backgroundExecutor.execute {
            try {
                repository.ingestSms(sender, body, timestamp, System.currentTimeMillis())
            } catch (error: Throwable) {
                repository.database.recordDiagnostic(
                    "RECEIVER_ERROR", repository.registry.normalizeSender(sender), null,
                    error.javaClass.simpleName.take(80))
            } finally {
                pending.finish()
            }
        }
    }
}
