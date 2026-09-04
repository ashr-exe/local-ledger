package dev.localledger.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import dev.localledger.sms.TransactionParser
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class LedgerRepository(private val context: Context) {
    val registry = BankRegistry(context)
    val database = LedgerDatabase(context)
    val backgroundExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-ledger-db").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun ingestSms(sender: String, body: String, smsTimestamp: Long, receivedAt: Long): Boolean {
        if (registry.isPromotionalSender(sender)) return false
        val bank = registry.bankForSender(sender) ?: return false
        if (!database.hasAccount(bank.id)) return false
        if (smsTimestamp < database.trackingStartedAt()) return false
        val header = registry.normalizeSender(sender)
        val sourceKey = TransactionParser.sourceKey(header, body, smsTimestamp)
        val parsed = TransactionParser.parse(body, smsTimestamp)
            ?.takeIf { it.occurredAt >= database.trackingStartedAt() }
        val inserted = if (parsed == null) {
            if (TransactionParser.looksLikeTransaction(body)) {
                database.noteUnparsed(sourceKey, bank.id, header, receivedAt)
            }
            false
        } else {
            database.insertTransaction(sourceKey, bank.id, parsed, header, receivedAt)
        }
        if (inserted) notifyChanged()
        return inserted
    }

    fun scanInbox(onFinished: (Int) -> Unit = {}) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            onFinished(0)
            return
        }
        backgroundExecutor.execute {
            var imported = 0
            val start = database.trackingStartedAt()
            val projection = arrayOf(
                Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.DATE_SENT,
            )
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE}>=?",
                arrayOf(start.toString()),
                "${Telephony.Sms.DATE} ASC",
            )?.use { cursor ->
                val senderIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val sentIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
                while (cursor.moveToNext()) {
                    val receivedAt = cursor.getLong(dateIndex)
                    val sentAt = cursor.getLong(sentIndex).takeIf { it > 0 } ?: receivedAt
                    if (ingestSms(cursor.getString(senderIndex), cursor.getString(bodyIndex), sentAt, receivedAt)) imported++
                }
            }
            mainHandler.post { onFinished(imported) }
        }
    }

    fun <T> query(block: LedgerDatabase.() -> T, onResult: (T) -> Unit) {
        backgroundExecutor.execute {
            val result = database.block()
            mainHandler.post { onResult(result) }
        }
    }

    fun mutate(block: LedgerDatabase.() -> Unit, onDone: () -> Unit = {}) {
        backgroundExecutor.execute {
            database.block()
            notifyChanged()
            mainHandler.post(onDone)
        }
    }

    private fun notifyChanged() {
        mainHandler.post { listeners.forEach { it.invoke() } }
    }
}
