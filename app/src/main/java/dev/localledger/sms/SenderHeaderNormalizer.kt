package dev.localledger.sms

import java.util.Locale

object SenderHeaderNormalizer {
    fun normalize(rawSender: String): String {
        var value = rawSender.trim().uppercase(Locale.ROOT).replace(" ", "")
        if (value.length >= 4 && value[2] == '-' && value[0].isLetter() && value[1].isLetter()) {
            value = value.substring(3)
        }
        if (value.length > 2 && value[value.length - 2] == '-' && value.last() in "PSTG") {
            value = value.dropLast(2)
        }
        return value
    }
}
