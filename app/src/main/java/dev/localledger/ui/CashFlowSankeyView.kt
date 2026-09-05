package dev.localledger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import dev.localledger.data.CategorySpend
import kotlin.math.max

class CashFlowSankeyView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF334139.toInt()
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private var inflow = 0L
    private var categories: List<CategorySpend> = emptyList()
    private var privateLabels = false

    fun submit(inflowMinor: Long, spending: List<CategorySpend>, hideValues: Boolean) {
        inflow = inflowMinor
        categories = spending.filter { it.spentMinor > 0 }.take(5)
        privateLabels = hideValues
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val left = 12f * density
        val right = width - 12f * density
        val nodeWidth = 8f * density
        val top = 25f * density
        val bottom = height - 18f * density
        val expenses = categories.sumOf { it.spentMinor }
        val total = max(max(inflow, expenses), 1L)

        paint.color = 0xFF2D6A4F.toInt()
        canvas.drawRoundRect(left, top, left + nodeWidth, bottom, 4f * density, 4f * density, paint)
        canvas.drawText("IN", left, 15f * density, textPaint)

        if (categories.isEmpty()) {
            textPaint.color = 0xFF66736B.toInt()
            canvas.drawText("Your income and spending paths will appear here.", 28f * density, height / 2f, textPaint)
            return
        }

        var cursorY = top
        val gap = 7f * density
        val usable = bottom - top - gap * (categories.size - 1)
        categories.forEach { item ->
            val segment = max(12f * density, usable * item.spentMinor / total.toFloat())
            val center = (cursorY + segment / 2f).coerceAtMost(bottom)
            paint.color = item.category.color
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = max(4f * density, 14f * density * item.spentMinor / total.toFloat())
            paint.alpha = 190
            path.reset()
            path.moveTo(left + nodeWidth, height / 2f)
            path.cubicTo(width * .38f, height / 2f, width * .62f, center, right - nodeWidth, center)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            canvas.drawRoundRect(right - nodeWidth, center - segment / 2f, right, center + segment / 2f,
                4f * density, 4f * density, paint)
            textPaint.color = 0xFF334139.toInt()
            val label = item.category.name + if (privateLabels) "" else "  " + compact(item.spentMinor)
            canvas.drawText(label, width * .53f, center - 7f * density, textPaint)
            cursorY += segment + gap
        }
    }

    private fun compact(value: Long): String = when {
        value >= 10_000_000 -> "₹%.1fL".format(value / 10_000_000.0)
        value >= 100_000 -> "₹%.1fk".format(value / 100_000.0)
        else -> "₹" + value / 100
    }
}
