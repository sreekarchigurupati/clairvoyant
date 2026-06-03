package com.clairvoyant.glasses.scanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.clairvoyant.glasses.R

/**
 * Custom overlay view that draws a scanning viewfinder with animated scan line.
 * Optimized for AR glasses with high-contrast neon borders.
 */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = context.getColor(R.color.scanner_border)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = context.getColor(R.color.primary)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val scanLinePaint = Paint().apply {
        color = context.getColor(R.color.primary_variant)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val viewfinderRect = RectF()
    private var scanLineY = 0f
    private val cornerLength = 40f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            scanLineY = viewfinderRect.top +
                (viewfinderRect.height() * (anim.animatedValue as Float))
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Center a square viewfinder, sized to ~60% of the smaller dimension
        val size = (minOf(w, h) * 0.55f)
        val cx = w / 2f
        val cy = h / 2f
        viewfinderRect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Draw darkened overlay around viewfinder
        canvas.save()
        canvas.clipOutRect(
            viewfinderRect.left, viewfinderRect.top,
            viewfinderRect.right, viewfinderRect.bottom
        )
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
        canvas.restore()

        // Draw viewfinder border
        canvas.drawRect(viewfinderRect, borderPaint)

        // Draw corner accents
        drawCorners(canvas)

        // Draw animated scan line
        if (scanLineY in viewfinderRect.top..viewfinderRect.bottom) {
            scanLinePaint.alpha = (255 * (1 - Math.abs(
                (scanLineY - viewfinderRect.centerY()) / (viewfinderRect.height() / 2)
            ))).toInt().coerceIn(80, 255)
            canvas.drawLine(
                viewfinderRect.left + 8, scanLineY,
                viewfinderRect.right - 8, scanLineY,
                scanLinePaint
            )
        }
    }

    private fun drawCorners(canvas: Canvas) {
        val r = viewfinderRect
        // Top-left
        canvas.drawLine(r.left, r.top + cornerLength, r.left, r.top, cornerPaint)
        canvas.drawLine(r.left, r.top, r.left + cornerLength, r.top, cornerPaint)
        // Top-right
        canvas.drawLine(r.right - cornerLength, r.top, r.right, r.top, cornerPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + cornerLength, cornerPaint)
        // Bottom-left
        canvas.drawLine(r.left, r.bottom - cornerLength, r.left, r.bottom, cornerPaint)
        canvas.drawLine(r.left, r.bottom, r.left + cornerLength, r.bottom, cornerPaint)
        // Bottom-right
        canvas.drawLine(r.right - cornerLength, r.bottom, r.right, r.bottom, cornerPaint)
        canvas.drawLine(r.right, r.bottom - cornerLength, r.right, r.bottom, cornerPaint)
    }
}
