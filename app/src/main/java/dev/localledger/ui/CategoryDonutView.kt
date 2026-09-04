package dev.localledger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import dev.localledger.data.CategorySpend

class CategoryDonutView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bounds = RectF()
    private var values: List<CategorySpend> = emptyList()

    fun submit(items: List<CategorySpend>) {
        values = items.filter { it.spentMinor > 0 }.take(7)
        contentDescription = values.joinToString { "${it.category.name}: ${it.spentMinor / 100.0}" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = width.coerceAtMost(height) * 0.13f
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        val inset = stroke / 2f + 4f
        bounds.set(inset, inset, width - inset, height - inset)
        val total = values.sumOf { it.spentMinor }.toFloat()
        if (total <= 0f) {
            paint.color = 0xFFE3E7E3.toInt()
            canvas.drawArc(bounds, -90f, 360f, false, paint)
            return
        }
        var start = -90f
        values.forEach { item ->
            val sweep = item.spentMinor / total * 360f
            paint.color = item.category.color
            canvas.drawArc(bounds, start + 1.5f, (sweep - 3f).coerceAtLeast(1f), false, paint)
            start += sweep
        }
    }
}
