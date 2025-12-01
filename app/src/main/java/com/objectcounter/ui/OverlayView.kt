package com.objectcounter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.objectcounter.ml.Detection

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<Detection> = emptyList()

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 36f
        isFakeBoldText = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    fun setDetections(detections: List<Detection>) {
        this.detections = detections
        invalidate()
    }

    fun clearDetections() {
        this.detections = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        detections.forEachIndexed { index, detection ->
            val left = detection.x * width
            val top = detection.y * height
            val right = left + detection.width * width
            val bottom = top + detection.height * height

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Draw number
            canvas.drawText("${index + 1}", left + 8, top + 36, textPaint)
        }
    }
}
