package dev.localledger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.localledger.data.DailyTotal
import kotlin.math.max

class SpendTrendView(context: Context) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE76F51.toInt(); style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22E76F51 }
    private val path = Path()
    private val area = Path()
    private var values: List<DailyTotal> = emptyList()

    fun submit(items: List<DailyTotal>) { values = items; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val pad = 8f * resources.displayMetrics.density
        val maximum = max(values.maxOf { it.amountMinor }, 1L)
        path.reset()
        values.forEachIndexed { index, item ->
            val x = if (values.size == 1) width / 2f
            else pad + (width - pad * 2) * index / (values.size - 1f)
            val y = height - pad - (height - pad * 2) * item.amountMinor / maximum.toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
        area.set(path)
        area.lineTo(width - pad, height - pad)
        area.lineTo(pad, height - pad)
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
    }
}
