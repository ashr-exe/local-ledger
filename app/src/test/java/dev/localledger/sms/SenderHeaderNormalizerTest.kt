package dev.localledger.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SenderHeaderNormalizerTest {
    @Test fun removesCarrierCirclePrefixAndMessageSuffix() {
        assertEquals("ICICIT", SenderHeaderNormalizer.normalize("AX-ICICIT-S"))
        assertEquals("DCBANK", SenderHeaderNormalizer.normalize("VD-DCBANK-T"))
        assertEquals("SBIUPI", SenderHeaderNormalizer.normalize("JD-SBIUPI-S"))
        assertEquals("CBSSBI", SenderHeaderNormalizer.normalize("JX-CBSSBI-S"))
    }
}
