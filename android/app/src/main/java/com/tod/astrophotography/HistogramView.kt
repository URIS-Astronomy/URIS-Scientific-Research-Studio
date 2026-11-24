package com.tod.astrophotography

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class HistogramView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var bins = IntArray(0)
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff78b2ff.toInt()
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    fun update(values: IntArray) {
        bins = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bins.isEmpty()) return
        val scale = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
        path.reset()
        bins.forEachIndexed { i, count ->
            val x = i.toFloat() * width / (bins.size - 1)
            val y = height - 2f - count.toFloat() / scale * (height - 4f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
