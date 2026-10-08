package com.example.autoclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import kotlin.math.abs

class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var boxLeft = 100f
    private var boxTop = 100f
    private var boxRight = 300f
    private var boxBottom = 300f

    private val handleTouchSize = 60f
    private val minSize = 24f
    private var dragMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class DragMode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val handlePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    fun resetBox() {
        post {
            boxLeft = width * 0.35f
            boxTop = height * 0.35f
            boxRight = width * 0.65f
            boxBottom = height * 0.65f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, boxPaint)
        canvas.drawCircle(boxLeft, boxTop, 18f, handlePaint)
        canvas.drawCircle(boxRight, boxTop, 18f, handlePaint)
        canvas.drawCircle(boxLeft, boxBottom, 18f, handlePaint)
        canvas.drawCircle(boxRight, boxBottom, 18f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = when {
                    isNear(x, y, boxLeft, boxTop) -> DragMode.RESIZE_TL
                    isNear(x, y, boxRight, boxTop) -> DragMode.RESIZE_TR
                    isNear(x, y, boxLeft, boxBottom) -> DragMode.RESIZE_BL
                    isNear(x, y, boxRight, boxBottom) -> DragMode.RESIZE_BR
                    x in boxLeft..boxRight && y in boxTop..boxBottom -> DragMode.MOVE
                    else -> DragMode.NONE
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                when (dragMode) {
                    DragMode.MOVE -> {
                        // ย้ายทั้งกรอบ แต่ไม่ให้หลุดขอบ view
                        val mx = dx.coerceIn(-boxLeft, width - boxRight)
                        val my = dy.coerceIn(-boxTop, height - boxBottom)
                        boxLeft += mx; boxRight += mx
                        boxTop += my; boxBottom += my
                    }
                    DragMode.RESIZE_TL -> {
                        boxLeft = (boxLeft + dx).coerceIn(0f, boxRight - minSize)
                        boxTop = (boxTop + dy).coerceIn(0f, boxBottom - minSize)
                    }
                    DragMode.RESIZE_TR -> {
                        boxRight = (boxRight + dx).coerceIn(boxLeft + minSize, width.toFloat())
                        boxTop = (boxTop + dy).coerceIn(0f, boxBottom - minSize)
                    }
                    DragMode.RESIZE_BL -> {
                        boxLeft = (boxLeft + dx).coerceIn(0f, boxRight - minSize)
                        boxBottom = (boxBottom + dy).coerceIn(boxTop + minSize, height.toFloat())
                    }
                    DragMode.RESIZE_BR -> {
                        boxRight = (boxRight + dx).coerceIn(boxLeft + minSize, width.toFloat())
                        boxBottom = (boxBottom + dy).coerceIn(boxTop + minSize, height.toFloat())
                    }
                    DragMode.NONE -> {}
                }
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = DragMode.NONE
        }
        return true
    }

    private fun isNear(x: Float, y: Float, targetX: Float, targetY: Float): Boolean {
        return abs(x - targetX) < handleTouchSize && abs(y - targetY) < handleTouchSize
    }

    /** แปลงกรอบบนหน้าจอเป็นพิกัดพิกเซลบนภาพจริง (ImageView แบบ fitCenter) */
    fun getCropRectOnBitmap(imageView: ImageView, bitmap: Bitmap): Rect {
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        val bmpWidth = bitmap.width.toFloat()
        val bmpHeight = bitmap.height.toFloat()

        val scale = minOf(viewWidth / bmpWidth, viewHeight / bmpHeight)
        val displayedWidth = bmpWidth * scale
        val displayedHeight = bmpHeight * scale
        val offsetX = (viewWidth - displayedWidth) / 2f
        val offsetY = (viewHeight - displayedHeight) / 2f

        val left = ((minOf(boxLeft, boxRight) - offsetX) / scale).toInt().coerceIn(0, bitmap.width)
        val top = ((minOf(boxTop, boxBottom) - offsetY) / scale).toInt().coerceIn(0, bitmap.height)
        val right = ((maxOf(boxLeft, boxRight) - offsetX) / scale).toInt().coerceIn(0, bitmap.width)
        val bottom = ((maxOf(boxTop, boxBottom) - offsetY) / scale).toInt().coerceIn(0, bitmap.height)

        return Rect(left, top, right, bottom)
    }
}
