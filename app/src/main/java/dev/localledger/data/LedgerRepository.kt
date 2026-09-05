package dev.localledger.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.os.Build
import dev.localledger.sms.TransactionParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        val header = registry.normalizeSender(sender)
        if (registry.isPromotionalSender(sender)) {
            database.recordDiagnostic("IGNORED_PROMOTIONAL", header, null, "sender suffix=P")
            return false
        }
        val bank = registry.bankForSender(sender)
        if (bank == null) {
            database.recordDiagnostic("UNKNOWN_SENDER", header, null, "allowlistMatch=false")
            return false
        }
        if (!database.hasAccount(bank.id)) {
            database.recordDiagnostic("BANK_NOT_CONFIGURED", header, bank.id, "allowlistMatch=true")
            return false
        }
        if (smsTimestamp < database.trackingStartedAt()) {
            database.recordDiagnostic("BEFORE_TRACKING", header, bank.id, "message predates tracking start")
            return false
        }
        val sourceKey = TransactionParser.sourceKey(header, body, smsTimestamp)
        val parsed = TransactionParser.parse(body, smsTimestamp, bank.id)
            ?.takeIf { it.occurredAt >= database.trackingStartedAt() }
        val inserted = if (parsed == null) {
            if (TransactionParser.looksLikeTransaction(body)) {
                database.noteUnparsed(sourceKey, bank.id, header, receivedAt)
            }
            database.recordDiagnostic(
                "NOT_PARSED", header, bank.id, TransactionParser.diagnosticSignals(body, bank.id))
            false
        } else {
            database.insertTransaction(sourceKey, bank.id, parsed, header, receivedAt)
        }
        if (inserted) {
            database.recordDiagnostic(
                "IMPORTED", header, bank.id,
                "direction=" + parsed?.direction + ";usedSmsTime=" + parsed?.usedSmsTime +
                    ";parser=" + parsed?.parserId + ";confidence=" + parsed?.confidence)
            BudgetNotifier.notify(context, database)
            notifyChanged()
        } else if (parsed != null) {
            database.recordDiagnostic("DUPLICATE_OR_DB_REJECTED", header, bank.id, "parsed=true")
        }
        return inserted
    }

    fun recordReceiverIssue(outcome: String, detail: String) {
        backgroundExecutor.execute { database.recordDiagnostic(outcome, null, null, detail) }
    }

    fun scanInbox(onFinished: (Int) -> Unit = {}) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            onFinished(0)
            return
        }
        backgroundExecutor.execute {
            var imported = 0
            var examined = 0
            try {
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
                        examined++
                        val receivedAt = cursor.getLong(dateIndex)
                        val sentAt = cursor.getLong(sentIndex).takeIf { it > 0 } ?: receivedAt
                        val sender = cursor.getString(senderIndex) ?: continue
                        val body = cursor.getString(bodyIndex) ?: continue
                        if (ingestSms(sender, body, sentAt, receivedAt)) imported++
                    }
                }
                database.recordDiagnostic("INBOX_SCAN", null, null, "examined=" + examined + ";imported=" + imported)
            } catch (error: Throwable) {
                database.recordDiagnostic("INBOX_SCAN_ERROR", null, null, "exception=" + error.javaClass.simpleName)
            }
            mainHandler.post { onFinished(imported) }
        }
    }

    fun diagnosticReport(onResult: (String) -> Unit) = query({
        val receive = context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val read = context.checkSelfPermission(Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        buildString {
            appendLine("Local Ledger diagnostic report")
            appendLine("Generated: " + REPORT_TIME.format(Instant.now().atZone(ZoneId.systemDefault())))
            appendLine("App: " + version + " (" + context.packageName + ")")
            appendLine("Android: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT)
            appendLine("SMS permissions: receive=" + receive + ", read=" + read)
            appendLine("Tracking started: " + trackingStartedAt())
            appendLine("Configured banks: " + accounts().joinToString { it.bankId })
            appendLine("Registry: " + registry.sourceName + " / " + registry.sourceDate)
            appendLine("Privacy: no SMS bodies, amounts, balances, account digits, references, or names included")
            appendLine()
            appendLine("Recent pipeline events (max 120; retained 7 days):")
            diagnostics().forEach { event ->
                append(REPORT_TIME.format(Instant.ofEpochMilli(event.recordedAt).atZone(ZoneId.systemDefault())))
                append(" | "); append(event.outcome)
                append(" | sender="); append(event.senderHeader ?: "-")
                append(" | bank="); append(event.bankId ?: "-")
                append(" | "); appendLine(event.detail)
            }
        }
    }, onResult)

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

    companion object {
        private val REPORT_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z")
    }
}
