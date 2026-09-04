package dev.localledger.sms

import dev.localledger.data.ParsedTransaction
import dev.localledger.data.TransactionDirection
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

object TransactionParser {
    private val amountPattern = Regex(
        "(?:₹|(?i:rs\\.?|inr))\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    )
    private val creditPattern = Regex(
        "(?i)\\b(credited|received|deposited|refund(?:ed)?|reversal|reversed|cashback|cr)\\b"
    )
    private val debitPattern = Regex(
        "(?i)\\b(debited|spent|paid|purchase(?:d)?|withdrawn|sent|transferred|dr)\\b"
    )
    private val accountContextPattern = Regex("(?i)\\b(a/?c|acct|account)\\b")
    private val failurePattern = Regex(
        "(?i)\\b(declined|failed|unsuccessful|could not be processed|cancelled)\\b"
    )
    private val nonTransactionPattern = Regex(
        "(?i)\\b(otp|one time password|available credit limit|payment due|statement generated|offer|apply now|pre-approved|eligible for|instant loan|discount|sale)\\b"
    )
    private val unsupportedInstrumentPattern = Regex(
        "(?i)\\b(credit card|card account|card ending|cc ending)\\b"
    )
    private val merchantPatterns = listOf(
        Regex("(?i)\\b(?:paid|sent|transferred)\\s+(?:to\\s+)?([a-z0-9][a-z0-9@._&+*/ -]{1,47}?)(?=\\s+(?:on|via|using|ref|upi|txn)\\b|[.;,]|$)"),
        Regex("(?i)\\b(?:at|to|towards|info[: -])\\s*([a-z0-9][a-z0-9@._&+*/ -]{1,47}?)(?=\\s+(?:on|via|using|ref|upi|avl|available|balance|txn|transaction|from)\\b|[.;,]|$)"),
        Regex("(?i)\\b(?:vpa|upi id)[: -]+([a-z0-9._-]+@[a-z0-9._-]+)"),
    )
    private val dateTimeCandidates = listOf(
        DateCandidate(
            Regex("\\b([0-3]?[0-9][/-][01]?[0-9][/-](?:20)?[0-9]{2})[ ,T]+([0-2]?[0-9]:[0-5][0-9](?::[0-5][0-9])?)\\b"),
            listOf("d/M/uuuu H:mm:ss", "d/M/uuuu H:mm", "d/M/uu H:mm:ss", "d/M/uu H:mm", "d-M-uuuu H:mm:ss", "d-M-uuuu H:mm", "d-M-uu H:mm:ss", "d-M-uu H:mm")
        ),
        DateCandidate(
            Regex("(?i)\\b([0-3]?[0-9]-[a-z]{3}-(?:20)?[0-9]{2})[ ,]+([0-2]?[0-9]:[0-5][0-9](?::[0-5][0-9])?)\\b"),
            listOf("d-MMM-uuuu H:mm:ss", "d-MMM-uuuu H:mm", "d-MMM-uu H:mm:ss", "d-MMM-uu H:mm")
        ),
    )

    fun parse(body: String, receivedAt: Long): ParsedTransaction? {
        val compact = body.replace(Regex("\\s+"), " ").trim()
        if (unsupportedInstrumentPattern.containsMatchIn(compact)) return null
        val hasAccountContext = accountContextPattern.containsMatchIn(compact)
        val isCredit = creditPattern.containsMatchIn(compact) ||
            (hasAccountContext && Regex("(?i)\\bcredit\\b").containsMatchIn(compact))
        val isDebit = debitPattern.containsMatchIn(compact) ||
            (hasAccountContext && Regex("(?i)\\bdebit\\b").containsMatchIn(compact))
        if (!isCredit && !isDebit) return null
        if (!isCredit && failurePattern.containsMatchIn(compact)) return null
        if (nonTransactionPattern.containsMatchIn(compact)) return null

        val amountText = amountPattern.find(compact)?.groupValues?.get(1) ?: return null
        val amountMinor = runCatching {
            BigDecimal(amountText.replace(",", ""))
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull()?.takeIf { it > 0 } ?: return null

        val direction = when {
            Regex("(?i)\\b(refund(?:ed)?|reversal|reversed|cashback)\\b").containsMatchIn(compact) -> TransactionDirection.CREDIT
            isDebit -> TransactionDirection.DEBIT
            else -> TransactionDirection.CREDIT
        }
        val occurredAt = parseDateTime(compact) ?: receivedAt
        val merchant = extractMerchant(compact, direction)
        return ParsedTransaction(
            amountMinor = amountMinor,
            direction = direction,
            occurredAt = occurredAt,
            merchant = merchant,
            merchantKey = normalizeMerchant(merchant),
            status = "POSTED",
            usedSmsTime = occurredAt == receivedAt,
        )
    }

    fun looksLikeTransaction(body: String): Boolean {
        val compact = body.replace(Regex("\\s+"), " ").trim()
        val hasAccountContext = accountContextPattern.containsMatchIn(compact)
        val hasDirection = creditPattern.containsMatchIn(compact) || debitPattern.containsMatchIn(compact) ||
            (hasAccountContext && Regex("(?i)\\b(credit|debit)\\b").containsMatchIn(compact))
        return amountPattern.containsMatchIn(compact) && hasDirection &&
            !nonTransactionPattern.containsMatchIn(compact) &&
            !unsupportedInstrumentPattern.containsMatchIn(compact)
    }

    fun sourceKey(sender: String, body: String, smsTimestamp: Long): String {
        val canonical = sender.trim().uppercase(Locale.ROOT) + "\u0000" +
            body.replace(Regex("\\s+"), " ").trim() + "\u0000" + smsTimestamp
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun normalizeMerchant(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9@]+"), " ")
        .trim()
        .ifBlank { "unknown" }

    private fun extractMerchant(body: String, direction: TransactionDirection): String {
        merchantPatterns.forEach { pattern ->
            val result = pattern.find(body)?.groupValues?.getOrNull(1)?.trim(' ', '-', ':')
            if (!result.isNullOrBlank() && result.length >= 2) return result.take(48)
        }
        return if (direction == TransactionDirection.CREDIT) "Incoming transfer" else "Digital payment"
    }

    private fun parseDateTime(body: String): Long? {
        for (candidate in dateTimeCandidates) {
            val match = candidate.pattern.find(body) ?: continue
            val value = "${match.groupValues[1]} ${match.groupValues[2]}"
            for (format in candidate.formats) {
                val formatter = DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(format)
                    .toFormatter(Locale.ENGLISH)
                val parsed = runCatching { LocalDateTime.parse(value, formatter) }.getOrNull()
                if (parsed != null) {
                    return parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }
        }
        return null
    }

    private data class DateCandidate(val pattern: Regex, val formats: List<String>)
}
