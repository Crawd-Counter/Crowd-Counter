package com.objectcounter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.objectcounter.ml.Detection

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<Detection> = emptyList()
    
    // Photo mode bounds (for FIT_CENTER scaling)
    private var photoBounds: RectF? = null

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
        photoBounds = null
        invalidate()
    }
    
    /**
     * Set photo dimensions for proper overlay scaling in photo mode
     */
    fun setPhotoBounds(photoWidth: Int, photoHeight: Int) {
        // Calculate FIT_CENTER bounds
        val viewRatio = width.toFloat() / height.toFloat()
        val photoRatio = photoWidth.toFloat() / photoHeight.toFloat()
        
        val scaledWidth: Float
        val scaledHeight: Float
        
        if (photoRatio > viewRatio) {
            // Photo is wider - fit to width
            scaledWidth = width.toFloat()
            scaledHeight = width.toFloat() / photoRatio
        } else {
            // Photo is taller - fit to height
            scaledHeight = height.toFloat()
            scaledWidth = height.toFloat() * photoRatio
        }
        
        val left = (width - scaledWidth) / 2
        val top = (height - scaledHeight) / 2
        
        photoBounds = RectF(left, top, left + scaledWidth, top + scaledHeight)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val bounds = photoBounds
        
        detections.forEachIndexed { index, detection ->
            val left: Float
            val top: Float
            val right: Float
            val bottom: Float
            
            if (bounds != null) {
                // Photo mode - scale to photo bounds
                left = bounds.left + detection.x * bounds.width()
                top = bounds.top + detection.y * bounds.height()
                right = left + detection.width * bounds.width()
                bottom = top + detection.height * bounds.height()
            } else {
                // Camera mode - full view
                left = detection.x * width
                top = detection.y * height
                right = left + detection.width * width
                bottom = top + detection.height * height
            }

            canvas.drawRect(left, top, right, bottom, boxPaint)
            canvas.drawText("${index + 1}", left + 8, top + 36, textPaint)
        }
    }
}
