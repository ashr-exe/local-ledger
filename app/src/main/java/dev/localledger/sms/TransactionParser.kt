package dev.localledger.sms

import dev.localledger.data.ParsedTransaction
import dev.localledger.data.TransactionDirection
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

object TransactionParser {
    private val amountPattern = Regex(
        "(?:₹|(?i:rs\\.?|inr))\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    )
    private val directionAmountPattern = Regex(
        "(?i)\\b(?:debited|credited)\\s+(?:by|for|with)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
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
    private val upcomingPattern = Regex(
        "(?i)\\b(?:will|would|shall)\\s+(?:be\\s+)?(?:debited|deducted|charged|auto[- ]?debited)|" +
            "\\b(?:scheduled for|due on|is due)\\b"
    )
    private val otpPattern = Regex(
        "(?i)(?:\\botp\\s*:?\\s*\\d{3,8}\\b)|" +
            "(?:\\b(?:otp|one[- ]time password|verification code)\\b.{0,60}?(?:is|:)\\s*\\d{3,8}\\b)|" +
            "(?:\\b\\d{3,8}\\s+(?:is\\s+)?(?:the\\s+|your\\s+|an?\\s+)?(?:otp|one[- ]time password|verification code)\\b)|" +
            "(?:\\b(?:use|enter)\\s+(?:otp|one[- ]time password)\\s*:?\\s*\\d{3,8}\\b)"
    )
    private val nonTransactionPattern = Regex(
        "(?i)\\b(available credit limit|payment due|statement generated|offer|apply now|pre-approved|" +
            "eligible for|instant loan|discount|shop now|buy now|lucky draw|you have won|spend summary)\\b|" +
            "\\b(?:flat|up to|upto)\\s+(?:rs\\.?|inr|₹)|\\bget\\s+(?:a\\s+)?cashback\\b"
    )
    private val unsupportedInstrumentPattern = Regex(
        "(?i)\\b(credit card|card account|card ending|cc ending)\\b"
    )
    private val referencePatterns = listOf(
        Regex("(?i)\\bupi(?:\\s+ref\\.?)?[: ]+([a-z]{0,5}[0-9]{8,18})\\b"),
        Regex("(?i)\\bref(?:no|erence)?[.: -]*([a-z]{0,5}[0-9]{8,18})\\b"),
        Regex("(?i)\\butr(?:\\s+(?:no|number))?[.: -]*([a-z]{0,5}[0-9]{8,18})\\b"),
    )
    private val balanceClausePatterns = listOf(
        Regex("(?i)\\b(?:avl|avbl|available|total|updated|remaining)\\.?\\s*(?:credit\\s+)?" +
            "(?:bal(?:ance)?|lmt|limit)\\b[^0-9\\n]{0,30}(?:rs\\.?|inr|₹)?\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?"),
        Regex("(?i)\\b(?:balance|credit\\s+limit)\\s*(?:is|:)[^0-9\\n]{0,20}" +
            "(?:rs\\.?|inr|₹)?\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?"),
        Regex("(?i)\\bbal(?:ance)?\\s*(?:is|:)?\\s*(?:rs\\.?|inr|₹)\\s*" +
            "[0-9][0-9,]*(?:\\.[0-9]{1,2})?"),
    )
    private val templateRules = listOf(
        TemplateRule(
            "icici-account-debit-v1", setOf("icici-bank"), TransactionDirection.DEBIT,
            Regex("(?i)\\bacct\\s+\\S+\\s+debited\\s+for\\s+(?:rs\\.?|inr|₹)\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?).*?;\\s*(?<merchant>[a-z0-9][a-z0-9@._&+*/ -]{1,60}?)\\s+credited\\b")),
        TemplateRule(
            "icici-salary-credit-v1", setOf("icici-bank"), TransactionDirection.CREDIT,
            Regex("(?i)\\bacc(?:t)?\\s+\\S+\\s+is\\s+credited\\s+with\\s+salary\\s+of\\s+(?:rs\\.?|inr|₹)\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?)")),
        TemplateRule(
            "dcb-upi-debit-v1", setOf("dcb-bank"), TransactionDirection.DEBIT,
            Regex("(?i)^inr\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s+debited\\s+dcb\\s+a/?c\\s+\\S+;\\s*to\\s+(?<merchant>[^;]{2,64});")),
        TemplateRule(
            "dcb-upi-credit-v1", setOf("dcb-bank"), TransactionDirection.CREDIT,
            Regex("(?i)^inr\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s+credited\\s+to\\s+your\\s+dcb\\s+bank\\s+a/?c\\s+\\S+\\s+from\\s+upi\\s+id\\s+(?<merchant>[a-z0-9._-]+@[a-z0-9._-]+)")),
        TemplateRule(
            "sbi-upi-debit-v1", setOf("state-bank-of-india"), TransactionDirection.DEBIT,
            Regex("(?i)\\ba/?c\\s+\\S+\\s+debited\\s+by\\s+(?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?).*?\\btrf\\s+to\\s+(?<merchant>.+?)\\s+refno\\s*(?<reference>[0-9]{8,18})")),
        TemplateRule(
            "sbi-upi-credit-v1", setOf("state-bank-of-india"), TransactionDirection.CREDIT,
            Regex("(?i)\\ba/?c\\s+\\S+\\s+has\\s+credit\\s+for\\s+.*?\\bof\\s+(?:rs\\.?|inr|₹)\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?)")),
    )
    private val merchantPatterns = listOf(
        Regex("(?i);\\s*(?:to\\s+)?([a-z0-9][a-z0-9@._&+*/ -]{1,47}?)\\s+credited\\b"),
        Regex("(?i)\\btrf\\s+to\\s+([a-z0-9][a-z0-9@._&+*/ -]{1,47}?)(?=\\s+ref(?:no)?\\b|[.;,]|$)"),
        Regex("(?i)\\bfrom\\s+(?:upi\\s+id\\s*)?([a-z0-9._-]+@[a-z0-9._-]+)"),
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
    private val dateCandidates = listOf(
        DateOnlyCandidate(
            Regex("(?i)\\b([0-3]?[0-9]-[a-z]{3}-(?:20)?[0-9]{2})\\b"),
            listOf("d-MMM-uuuu", "d-MMM-uu")
        ),
        DateOnlyCandidate(
            Regex("(?i)\\b([0-3]?[0-9][a-z]{3}(?:20)?[0-9]{2})\\b"),
            listOf("dMMMuuuu", "dMMMuu")
        ),
        DateOnlyCandidate(
            Regex("\\b([0-3]?[0-9]/[01]?[0-9]/(?:20)?[0-9]{2})\\b"),
            listOf("d/M/uuuu", "d/M/uu")
        ),
    )

    fun parse(body: String, receivedAt: Long, bankId: String? = null): ParsedTransaction? {
        val compact = body.replace(Regex("\\s+"), " ").trim()
        if (unsupportedInstrumentPattern.containsMatchIn(compact)) return null
        if (otpPattern.containsMatchIn(compact)) return null
        if (failurePattern.containsMatchIn(compact)) return null
        if (upcomingPattern.containsMatchIn(compact)) return null
        if (nonTransactionPattern.containsMatchIn(compact)) return null
        val template = matchTemplate(compact, bankId)
        val hasAccountContext = accountContextPattern.containsMatchIn(compact)
        val isCredit = creditPattern.containsMatchIn(compact) ||
            (hasAccountContext && Regex("(?i)\\bcredit\\b").containsMatchIn(compact))
        val isDebit = debitPattern.containsMatchIn(compact) ||
            (hasAccountContext && Regex("(?i)\\bdebit\\b").containsMatchIn(compact))
        if (!isCredit && !isDebit && template == null) return null
        val amountSearchBody = maskBalanceClauses(compact)

        val amountText = template?.match?.namedGroup("amount") ?:
            (amountPattern.find(amountSearchBody) ?: directionAmountPattern.find(amountSearchBody))
                ?.groupValues?.get(1) ?: return null
        val amountMinor = runCatching {
            BigDecimal(amountText.replace(",", ""))
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull()?.takeIf { it > 0 } ?: return null

        val direction = when {
            template != null -> template.rule.direction
            Regex("(?i)\\b(refund(?:ed)?|reversal|reversed|cashback)\\b").containsMatchIn(compact) -> TransactionDirection.CREDIT
            isDebit -> TransactionDirection.DEBIT
            else -> TransactionDirection.CREDIT
        }
        val occurredAt = parseDateTime(compact, receivedAt) ?: receivedAt
        val merchant = template?.match?.namedGroup("merchant")
            ?.trim(' ', '-', ':', '.', ',', ';')?.take(64)
            ?.takeIf { it.length >= 2 } ?: extractMerchant(compact, direction)
        val parserId = template?.rule?.id ?: "generic-v2"
        return ParsedTransaction(
            amountMinor = amountMinor,
            direction = direction,
            occurredAt = occurredAt,
            merchant = merchant,
            merchantKey = normalizeMerchant(merchant),
            status = "POSTED",
            usedSmsTime = occurredAt == receivedAt,
            reference = template?.match?.namedGroup("reference") ?:
                referencePatterns.firstNotNullOfOrNull {
                pattern -> pattern.find(compact)?.groupValues?.getOrNull(1)
            },
            parserId = parserId,
            confidence = if (template != null) 95 else if (merchant.contains("transfer") ||
                merchant.contains("payment")) 60 else 75,
        )
    }

    fun looksLikeTransaction(body: String): Boolean {
        val compact = body.replace(Regex("\\s+"), " ").trim()
        if (otpPattern.containsMatchIn(compact) || failurePattern.containsMatchIn(compact) ||
            upcomingPattern.containsMatchIn(compact)) return false
        val hasAccountContext = accountContextPattern.containsMatchIn(compact)
        val hasDirection = creditPattern.containsMatchIn(compact) || debitPattern.containsMatchIn(compact) ||
            (hasAccountContext && Regex("(?i)\\b(credit|debit)\\b").containsMatchIn(compact))
        return (amountPattern.containsMatchIn(compact) || directionAmountPattern.containsMatchIn(compact)) && hasDirection &&
            !nonTransactionPattern.containsMatchIn(compact) &&
            !unsupportedInstrumentPattern.containsMatchIn(compact)
    }

    /** Privacy-safe parser state for diagnostics; never includes SMS content or extracted values. */
    fun diagnosticSignals(body: String, bankId: String? = null): String {
        val compact = body.replace(Regex("\\s+"), " ").trim()
        val account = accountContextPattern.containsMatchIn(compact)
        val direction = creditPattern.containsMatchIn(compact) || debitPattern.containsMatchIn(compact) ||
            (account && Regex("(?i)\\b(credit|debit)\\b").containsMatchIn(compact))
        val template = matchTemplate(compact, bankId)
        return listOf(
            "amount=${amountPattern.containsMatchIn(compact) || directionAmountPattern.containsMatchIn(compact)}",
            "direction=$direction",
            "account=$account",
            "failed=${failurePattern.containsMatchIn(compact)}",
            "otp=${otpPattern.containsMatchIn(compact)}",
            "upcoming=${upcomingPattern.containsMatchIn(compact)}",
            "nonTransaction=${nonTransactionPattern.containsMatchIn(compact)}",
            "unsupportedInstrument=${unsupportedInstrumentPattern.containsMatchIn(compact)}",
            "template=" + (template?.rule?.id ?: "none"),
            "bankProfiles=" + templateRules.count { bankId in it.bankIds },
            "length=${compact.length}",
        ).joinToString(",")
    }

    fun profileIds(bankId: String): List<String> =
        templateRules.filter { bankId in it.bankIds }.map { it.id }

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
            val result = pattern.find(body)?.groupValues?.getOrNull(1)?.trim(' ', '-', ':', '.', ',', ';')
            if (!result.isNullOrBlank() && result.length >= 2) return result.take(48)
        }
        return if (direction == TransactionDirection.CREDIT) "Incoming transfer" else "Digital payment"
    }

    private fun parseDateTime(body: String, receivedAt: Long): Long? {
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
        val received = Instant.ofEpochMilli(receivedAt).atZone(ZoneId.systemDefault())
        for (candidate in dateCandidates) {
            val match = candidate.pattern.find(body) ?: continue
            for (format in candidate.formats) {
                val formatter = DateTimeFormatterBuilder().parseCaseInsensitive()
                    .appendPattern(format).toFormatter(Locale.ENGLISH)
                val parsed = runCatching { LocalDate.parse(match.groupValues[1], formatter) }.getOrNull()
                if (parsed != null) {
                    return parsed.atTime(received.toLocalTime()).atZone(received.zone).toInstant().toEpochMilli()
                }
            }
        }
        return null
    }

    private data class DateCandidate(val pattern: Regex, val formats: List<String>)
    private data class DateOnlyCandidate(val pattern: Regex, val formats: List<String>)
    private data class TemplateRule(
        val id: String,
        val bankIds: Set<String>,
        val direction: TransactionDirection,
        val pattern: Regex,
    )
    private data class TemplateMatch(val rule: TemplateRule, val match: MatchResult)

    private fun matchTemplate(body: String, bankId: String?): TemplateMatch? {
        if (bankId == null) return null
        templateRules.asSequence().filter { bankId in it.bankIds }.forEach { rule ->
            rule.pattern.find(body)?.let { return TemplateMatch(rule, it) }
        }
        return null
    }

    private fun maskBalanceClauses(body: String): String = balanceClausePatterns.fold(body) { text, pattern ->
        pattern.replace(text) { " ".repeat(it.value.length) }
    }

    private fun MatchResult.namedGroup(name: String): String? =
        runCatching { groups[name]?.value }.getOrNull()
}
