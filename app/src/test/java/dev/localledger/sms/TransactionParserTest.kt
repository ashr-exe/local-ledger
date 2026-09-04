package dev.localledger.sms

import dev.localledger.data.TransactionDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionParserTest {
    @Test fun parsesDebit() {
        val parsed = TransactionParser.parse(
            "Rs.1,249.50 debited from A/c XX1234 and paid to FRESH MENU on 05-09-2026 20:14. Avl Bal Rs 9,000",
            1L,
        )
        assertNotNull(parsed)
        assertEquals(124950L, parsed!!.amountMinor)
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals("FRESH MENU", parsed.merchant)
    }

    @Test fun parsesCreditAndRefund() {
        val parsed = TransactionParser.parse(
            "INR 500.00 refunded to your account for transaction at SWIGGY",
            100L,
        )
        assertEquals(TransactionDirection.CREDIT, parsed!!.direction)
        assertEquals(50000L, parsed.amountMinor)
    }

    @Test fun rejectsOtpAndFailure() {
        assertNull(TransactionParser.parse("OTP 123456 for INR 900 payment", 1L))
        assertNull(TransactionParser.parse("Transaction of Rs 900 was declined", 1L))
    }

    @Test fun rejectsExplicitCreditCardSpendUntilCardsAreConfigured() {
        assertNull(TransactionParser.parse("Rs 999 spent on your credit card ending 1234 at STORE", 1L))
    }

    @Test fun parsesUpiCredit() {
        val parsed = TransactionParser.parse("Your a/c is credited by ₹2,500 via UPI from arun@okaxis", 42L)
        assertEquals(TransactionDirection.CREDIT, parsed!!.direction)
        assertEquals(250000L, parsed.amountMinor)
    }
}
