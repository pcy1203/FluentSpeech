package com.chanyoungpark.fluentspeech

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Voice Tension 시간축 그래프 커스텀 뷰
 */
class TensionGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var dataPoints: List<Int> = emptyList()

    // Paint
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#AAFFCC")
        strokeWidth = 3f
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#33FFFFFF")
        strokeWidth = 1f
        style       = Paint.Style.STROKE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.parseColor("#88FFFFFF")
        textSize = 24f
    }

    fun setData(points: List<Int>) {
        dataPoints = points
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w     = width.toFloat()
        val h     = height.toFloat()
        val padL  = 40f
        val padR  = 16f
        val padT  = 16f
        val padB  = 28f
        val graphW = w - padL - padR
        val graphH = h - padT - padB

        // ── 가로 그리드 (0%, 50%, 100%) ──
        for (pct in listOf(0, 50, 100)) {
            val y = padT + graphH * (1f - pct / 100f)
            canvas.drawLine(padL, y, padL + graphW, y, gridPaint)
            canvas.drawText("$pct%", 0f, y + 8f, labelPaint)
        }

        if (dataPoints.isEmpty()) {
            val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color    = Color.parseColor("#55FFFFFF")
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No data", w / 2f, h / 2f, noDataPaint)
            return
        }

        val n = dataPoints.size

        // ── Fill (그라디언트) ──
        val path = Path()
        val shader = LinearGradient(
            0f, padT, 0f, padT + graphH,
            Color.parseColor("#44AAFFCC"),
            Color.parseColor("#00AAFFCC"),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = shader

        for (i in dataPoints.indices) {
            val x = padL + graphW * i / (n - 1).coerceAtLeast(1)
            val y = padT + graphH * (1f - dataPoints[i] / 100f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fillPath = Path(path).apply {
            lineTo(padL + graphW, padT + graphH)
            lineTo(padL, padT + graphH)
            close()
        }
        canvas.drawPath(fillPath, fillPaint)

        // ── 라인 ──
        canvas.drawPath(path, linePaint)

        // ── 점 (마지막 데이터 포인트 강조) ──
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAFFCC")
            style = Paint.Style.FILL
        }
        val lastX = padL + graphW
        val lastY = padT + graphH * (1f - dataPoints.last() / 100f)
        canvas.drawCircle(lastX, lastY, 5f, dotPaint)
    }
}