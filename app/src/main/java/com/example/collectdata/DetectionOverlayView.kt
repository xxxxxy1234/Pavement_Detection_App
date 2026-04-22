package com.example.collectdata

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.RED
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 38f
        style = Paint.Style.FILL
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bgPaint = Paint().apply {
        color = Color.argb(180, 200, 0, 0)
        style = Paint.Style.FILL
    }

    var detections: List<DetectionResult> = emptyList()
    var frameWidth: Int = 640
    var frameHeight: Int = 480

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        val scaleX = width.toFloat() / frameWidth
        val scaleY = height.toFloat() / frameHeight

        for (d in detections) {
            val box = RectF(
                d.boundingBox.left * scaleX,
                d.boundingBox.top * scaleY,
                d.boundingBox.right * scaleX,
                d.boundingBox.bottom * scaleY
            )
            canvas.drawRect(box, boxPaint)
            val label = "${d.label} ${"%.0f".format(d.confidence * 100)}%"
            val textWidth = textPaint.measureText(label)
            canvas.drawRect(box.left, box.top - 46f,
                box.left + textWidth + 8f, box.top, bgPaint)
            canvas.drawText(label, box.left + 4f, box.top - 10f, textPaint)
        }
    }
}