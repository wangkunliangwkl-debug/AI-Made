package com.rcb.bypass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * v3.5b：圆形指针旋钮（仪表盘），重绘版。
 *
 * 绘制：所有元素共用一个圆心 (cx, cy)，半径基准统一，保证正圆、对称：
 *   - 外圈进度弧（270°，从 135° 扫到 405°）
 *   - 内圈刻度（每档一根细线，每 3 档一根粗线）
 *   - 从圆心伸出的实心指针
 *   - 圆心大数字「X%」
 *
 * 交互：把手指触点相对圆心的角度映射到档位（真正“转旋钮”的手感），
 *   档位变化给一次轻振动。角度越往下扫越大，顺时针 = 加档。
 */
class KnobView(
    context: Context,
    private val steps: List<Int>,
    private val onChanged: (Int) -> Unit
) : View(context) {

    private var index: Int = steps.indexOf(10).let { if (it < 0) 0 else it }

    // 角度：起始 135°（左下），扫 270°（到 405° = 右下）。
    private val startAngle = 135f
    private val sweepAngle = 270f

    private fun angleForValue(i: Int): Float {
        val frac = i.toFloat() / (steps.size - 1).coerceAtLeast(1)
        return startAngle + sweepAngle * frac
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(8f)
        color = Color.parseColor("#1A000000")
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(8f)
        color = Color.parseColor("#FF7A00")
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(1.6f)
        color = Color.parseColor("#4D000000")
    }
    private val tickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.6f)
        color = Color.parseColor("#66000000")
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(4f)
        color = Color.parseColor("#FF7A00")
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val hubRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#FF7A00")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#1A1A1A")
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#8A8A8A")
    }

    private val arcRect = RectF()

    private var vibrator: Vibrator? = null

    fun setValue(pct: Int) {
        val i = steps.indexOf(pct)
        if (i >= 0 && i != index) {
            index = i
            invalidate()
        }
    }

    fun currentPct(): Int = steps[index]

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        // 统一半径基准：进度弧按外圈，刻度/指针在其内侧。
        val outerRadius = min(width, height) / 2f - dp(12f)   // 进度弧半径
        val arcLineWidth = dp(8f)
        // 刻度环半径：从弧的内边缘向里缩一点
        val tickOuterRadius = outerRadius - arcLineWidth - dp(6f)
        val tickInnerRadius = tickOuterRadius - dp(11f)

        arcRect.set(
            cx - outerRadius, cy - outerRadius,
            cx + outerRadius, cy + outerRadius
        )
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, trackPaint)
        canvas.drawArc(
            arcRect, startAngle,
            sweepAngle * (index.toFloat() / (steps.size - 1).coerceAtLeast(1)),
            false, progressPaint
        )

        // 刻度
        for (i in steps.indices) {
            val a = Math.toRadians(angleForValue(i).toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            val major = i % 3 == 0
            val paint = if (major) tickMajorPaint else tickPaint
            canvas.drawLine(
                cx + c * tickInnerRadius, cy + s * tickInnerRadius,
                cx + c * tickOuterRadius, cy + s * tickOuterRadius,
                paint
            )
        }

        // 指针（从圆心附近伸到刻度环内侧）
        val na = Math.toRadians(angleForValue(index).toDouble())
        val nc = cos(na).toFloat()
        val ns = sin(na).toFloat()
        canvas.drawLine(
            cx + nc * dp(10f), cy + ns * dp(10f),
            cx + nc * tickInnerRadius, cy + ns * tickInnerRadius,
            needlePaint
        )

        // 圆心白点 + 橙色描边
        canvas.drawCircle(cx, cy, dp(9f), hubPaint)
        canvas.drawCircle(cx, cy, dp(9f), hubRingPaint)

        // 圆心数字（白点下方，避免遮挡）
        textPaint.textSize = dp(24f)
        val textBaseline = cy + dp(28f)
        canvas.drawText("${steps[index]}%", cx, textBaseline, textPaint)

        subPaint.textSize = dp(11f)
        canvas.drawText("阈值", cx, cy + dp(44f), subPaint)
    }

    private var dragging = false
    private var prevAngle = 0f     // 上一帧的原始 atan2 角度（[-180,180)）
    private var accumulated = 0f   // 累计连续转动角度（度，可跨 180° 断口）
    private var baseIndex = 0      // 按下时的档位

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                prevAngle = rawAngle(event.x, event.y)
                accumulated = 0f
                baseIndex = index
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val a = rawAngle(event.x, event.y)
                var d = a - prevAngle
                // 跨[-180,180)断口时，把增量规整到[-180,180)，保持角度连续
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                prevAngle = a
                accumulated += d
                // 每转 sweep/(size-1) 度换一档，从按下时的档位累积偏移
                val perStep = sweepAngle / (steps.size - 1).coerceAtLeast(1)
                val stepDelta = Math.round(accumulated / perStep).toInt()
                val next = (baseIndex + stepDelta).coerceIn(0, steps.size - 1)
                if (next != index) {
                    index = next
                    onChanged(steps[index])
                    invalidate()
                    vibrate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 触点相对圆心的原始角度（度，范围 [-180,180)，0=正右，顺时针为正）。 */
    private fun rawAngle(x: Float, y: Float): Float {
        val cx = width / 2f
        val cy = height / 2f
        return Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
    }

    private fun vibrate() {
        if (vibrator == null) {
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        try {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(16, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (_: Throwable) {
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}