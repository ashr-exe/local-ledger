package dev.localledger.data

import android.content.Context
import android.util.JsonReader
import java.io.InputStreamReader
import java.util.Locale
import dev.localledger.sms.SenderHeaderNormalizer

class BankRegistry(context: Context) {
    val banks: List<BankDefinition>
    val sourceName: String
    val sourceDate: String
    private val headerIndex: Map<String, BankDefinition>

    init {
        var source = "TRAI"
        var published = ""
        val loadedBanks = mutableListOf<BankDefinition>()
        context.assets.open("bank_sender_registry.json").use { stream ->
            JsonReader(InputStreamReader(stream)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "source" -> source = reader.nextString()
                        "published" -> published = reader.nextString()
                        "banks" -> readBanks(reader, loadedBanks)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        banks = loadedBanks.sortedBy { it.name.lowercase(Locale.ROOT) }
        sourceName = source
        sourceDate = published
        headerIndex = buildMap {
            banks.forEach { bank -> bank.headers.forEach { put(it, bank) } }
        }
    }

    fun bankForSender(rawSender: String?): BankDefinition? {
        val normalized = normalizeSender(rawSender ?: return null)
        return headerIndex[normalized]
    }

    fun isPromotionalSender(rawSender: String): Boolean =
        rawSender.trim().uppercase(Locale.ROOT).endsWith("-P")

    fun normalizeSender(rawSender: String): String = SenderHeaderNormalizer.normalize(rawSender)

    private fun readBanks(reader: JsonReader, output: MutableList<BankDefinition>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var id = ""
            var name = ""
            val headers = linkedSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = reader.nextString()
                    "name" -> name = reader.nextString()
                    "headers", "observedHeaders" -> {
                        reader.beginArray()
                        while (reader.hasNext()) headers += reader.nextString().uppercase(Locale.ROOT)
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (id.isNotBlank() && name.isNotBlank() && headers.isNotEmpty()) {
                output += BankDefinition(id, name, headers)
            }
        }
        reader.endArray()
    }
}
