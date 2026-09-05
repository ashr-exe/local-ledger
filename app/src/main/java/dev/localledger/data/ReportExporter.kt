package dev.localledger.data

import android.content.ContentResolver
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ReportExporter {
    private val date = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")

    fun writeCsv(resolver: ContentResolver, uri: Uri, transactions: List<LedgerTransaction>) {
        val output = resolver.openOutputStream(uri, "w")
            ?: error("The selected document could not be opened")
        output.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.appendLine("date,direction,amount_inr,bank,merchant,official_merchant,category,tags,reference,source,affects_balance,note")
                transactions.forEach { tx ->
                    writer.appendLine(listOf(
                        formatDate(tx.occurredAt), tx.direction.name,
                        BigDecimal(tx.amountMinor).movePointLeft(2).toPlainString(),
                        tx.bankName, tx.displayMerchant, tx.merchant, tx.categoryName,
                        tx.tags.joinToString("|") { it.name }, tx.reference.orEmpty(), tx.origin.name,
                        tx.affectsBalance.toString(), tx.note.orEmpty()
                    ).joinToString(",") { csv(it) })
                }
            }
        }
    }

    fun writePdf(resolver: ContentResolver, uri: Uri, transactions: List<LedgerTransaction>) {
        val document = PdfDocument()
        try {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF17211B.toInt(); textSize = 22f; isFakeBoldText = true }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF334139.toInt(); textSize = 10f }
        val muted = Paint(body).apply { color = 0xFF66736B.toInt(); textSize = 9f }
        val debitTotal = transactions.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountMinor }
        val creditTotal = transactions.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountMinor }
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        var canvas = page.canvas
        var y = 54f

        fun header() {
            canvas.drawText("Local Ledger report", 42f, y, title); y += 22f
            canvas.drawText("Generated locally · " + formatDate(System.currentTimeMillis()), 42f, y, muted); y += 26f
            canvas.drawText("Inflow ₹" + formatMoney(creditTotal) + "    Outflow ₹" + formatMoney(debitTotal) +
                "    Entries " + transactions.size, 42f, y, body); y += 28f
            canvas.drawLine(42f, y, 553f, y, Paint().apply { color = 0xFFE3E7E3.toInt() }); y += 18f
        }
        fun newPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            canvas = page.canvas; y = 48f
            canvas.drawText("Local Ledger report · page " + pageNumber, 42f, y, muted); y += 24f
        }

        header()
        transactions.forEach { tx ->
            if (y > 795f) newPage()
            val sign = if (tx.direction == TransactionDirection.CREDIT) "+" else "−"
            canvas.drawText(ellipsize(tx.displayMerchant, 34), 42f, y, body)
            canvas.drawText(sign + "₹" + BigDecimal(tx.amountMinor).movePointLeft(2).toPlainString(), 450f, y, body)
            y += 14f
            canvas.drawText(ellipsize(formatDate(tx.occurredAt) + " · " + tx.bankName + " · " +
                tx.categoryName + tagSuffix(tx), 82), 42f, y, muted)
            y += 17f
        }
        document.finishPage(page)
        val output = resolver.openOutputStream(uri, "w")
            ?: error("The selected document could not be opened")
        output.use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun tagSuffix(tx: LedgerTransaction): String =
        if (tx.tags.isEmpty()) "" else " · #" + tx.tags.joinToString(" #") { it.name }
    private fun formatDate(value: Long): String =
        date.format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
    private fun ellipsize(value: String, limit: Int) =
        if (value.length <= limit) value else value.take(limit - 1) + "…"
    private fun formatMoney(value: Long): String =
        BigDecimal(value).movePointLeft(2).stripTrailingZeros().toPlainString()
    private fun csv(value: String): String {
        val safe = when (value.firstOrNull()) {
            '=', '+', '-', '@', '\t', '\r' -> "'" + value
            else -> value
        }
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
