package dev.localledger.sms

import dev.localledger.data.TransactionDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Grow-only invented cases for messages that must never create phantom transactions. */
class TransactionParserAdversarialTest {
    @Test fun otpQuotingAPaymentIsNeverBooked() {
        assertNull(TransactionParser.parse(
            "Use OTP 998877 to authorize Rs.999.00 debited at SAMPLE SHOP on A/c XX1234.", 1L))
        assertNull(TransactionParser.parse(
            "OTP 112233 for INR 700.00 debit transaction on A/c XX1234.", 1L))
    }

    @Test fun transactionFooterMentioningOtpDoesNotHideARealDebit() {
        val parsed = TransactionParser.parse(
            "A/c XX1234 debited by INR 120.00 at SAMPLE CAFE. Never share OTP or PIN.", 1L)!!
        assertEquals(12_000L, parsed.amountMinor)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
    }

    @Test fun futureDebitIsNeverBooked() {
        assertNull(TransactionParser.parse(
            "INR 499.00 will be debited from A/c XX1234 on 10-Sep-26 for SAMPLE SUBSCRIPTION.", 1L))
    }

    @Test fun marketingWithMoneyWordsIsNeverBooked() {
        assertNull(TransactionParser.parse(
            "Paid too much? Get cashback of Rs.500.00. Shop now.", 1L))
    }

    @Test fun balanceBeforeTransactionAmountCannotBecomeTheTransaction() {
        val parsed = TransactionParser.parse(
            "Available balance INR 9,876.54. Your A/c XX1234 was debited by INR 45.00 at SAMPLE STORE.", 1L)!!
        assertEquals(4_500L, parsed.amountMinor)
        val abbreviated = TransactionParser.parse(
            "Bal INR 8,765.43. A/c XX1234 debited by INR 46.00 at SAMPLE STORE.", 1L)!!
        assertEquals(4_600L, abbreviated.amountMinor)
    }

    @Test fun alphanumericUtrRemainsAString() {
        val parsed = TransactionParser.parse(
            "INR 75.00 debited from A/c XX1234 to SAMPLE STORE. UTR N123456789012.", 1L)!!
        assertEquals("N123456789012", parsed.reference)
    }
}
