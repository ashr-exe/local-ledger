package dev.localledger.sms

import dev.localledger.data.TransactionDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

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

    @Test fun parsesIciciStyleDebitWhereCounterpartyIsCredited() {
        val parsed = TransactionParser.parse(
            "ICICI Bank Acct XX123 debited for Rs 42.00 on 05-Sep-26; CITY TRANSIT credited. UPI:600000000001.",
            1_788_558_000_000L,
            "icici-bank",
        )!!
        assertEquals(TransactionDirection.DEBIT, parsed.direction)
        assertEquals(4200L, parsed.amountMinor)
        assertEquals("CITY TRANSIT", parsed.merchant)
        assertEquals("icici-account-debit-v1", parsed.parserId)
        assertEquals(95, parsed.confidence)
        val date = Instant.ofEpochMilli(parsed.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals("2026-09-05", date.toString())
    }

    @Test fun parsesDcbStyleDebitAndCredit() {
        val debit = TransactionParser.parse(
            "INR 21.00 debited DCB a/c 1234; to SAMPLE SHOP; UPI 600000000002. Bal INR 999.00",
            100L,
            "dcb-bank",
        )!!
        assertEquals(TransactionDirection.DEBIT, debit.direction)
        assertEquals("SAMPLE SHOP", debit.merchant)
        assertEquals("dcb-upi-debit-v1", debit.parserId)

        val credit = TransactionParser.parse(
            "INR 650.00 credited to your DCB Bank a/c 1234 from UPI ID sample@oksbi. UPI ref. 600000000003.",
            100L,
            "dcb-bank",
        )!!
        assertEquals(TransactionDirection.CREDIT, credit.direction)
        assertEquals("sample@oksbi", credit.merchant)
        assertEquals("dcb-upi-credit-v1", credit.parserId)
    }

    @Test fun parsesSbiCompactDateDebitAndCredit() {
        val debit = TransactionParser.parse(
            "Dear UPI user A/C X1234 debited by 22.00 on date 27Aug26 trf to SAMPLE STORE Refno 600000000004-SBI",
            1_777_777_777_777L,
            "state-bank-of-india",
        )!!
        assertEquals(TransactionDirection.DEBIT, debit.direction)
        assertEquals("SAMPLE STORE", debit.merchant)
        assertEquals("sbi-upi-debit-v1", debit.parserId)
        val debitDate = Instant.ofEpochMilli(debit.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals("2026-08-27", debitDate.toString())

        val credit = TransactionParser.parse(
            "Your A/C XXXXX001234 has credit for UPI/DRC/600000000005/16082026/ of Rs 73.00 on 16/08/26. Avl Bal Rs 999.00-SBI",
            1_777_777_777_777L,
            "state-bank-of-india",
        )!!
        assertEquals(TransactionDirection.CREDIT, credit.direction)
        assertEquals(7300L, credit.amountMinor)
        assertEquals("sbi-upi-credit-v1", credit.parserId)
    }

    @Test fun exposesSupportedProfilesWithoutSmsContent() {
        assertEquals(
            listOf("icici-account-debit-v1", "icici-salary-credit-v1"),
            TransactionParser.profileIds("icici-bank"),
        )
        assertEquals(emptyList<String>(), TransactionParser.profileIds("unprofiled-bank"))
    }

    @Test fun rejectsFailedCreditLanguage() {
        assertNull(TransactionParser.parse("Credit of INR 800.00 failed for your account", 1L))
    }
}
