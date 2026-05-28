package com.chanyoungpark.fluentspeech

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class FaceGuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Paint 설정 ─────────────────────────────────────────────────
    private val ovalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.WHITE
        style  = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect  = DashPathEffect(floatArrayOf(20f, 12f), 0f)
        alpha  = 180
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 28f
        alpha     = 160
        textAlign = Paint.Align.CENTER
    }

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#66000000")
        style = Paint.Style.FILL
    }

    // ── 타원 영역 ──────────────────────────────────────────────────
    private val ovalRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // 타원: 가로 70%, 세로 80% 중앙 배치
        val ovalW = w * 0.70f
        val ovalH = h * 0.80f
        val left  = (w - ovalW) / 2f
        val top   = (h - ovalH) / 2f
        ovalRect.set(left, top, left + ovalW, top + ovalH)

        // 타원 바깥 어둡게
        canvas.drawRect(0f, 0f, w, h, dimPaint)

        // 타원 내부 클리어 (투명하게)
        val clearPaint = Paint().apply {
            color = Color.TRANSPARENT
            xfermode = android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR
            )
        }
        canvas.drawOval(ovalRect, clearPaint)

        // 점선 타원 테두리
        canvas.drawOval(ovalRect, ovalPaint)

        // 안내 텍스트
        canvas.drawText(
            "Align your face",
            w / 2f,
            top - 16f,
            labelPaint
        )
    }
}