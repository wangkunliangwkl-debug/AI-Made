package com.rcb.bypass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View

/**
 * 竖向（上下）iOS 风格开关，无过渡动画。
 *
 * 关闭（分）：橙色圆点在下，轨道为「橙色轮廓框」（仅描边，内部透明）；
 * 打开（合）：橙色圆点在上，轨道填满成「橙色药丸」。
 *
 * 无滑动过渡，状态直接跳变。无 ripple、无按压缩放（禁用 isClickable 的默认动效）。
 * 点击/上下滑动切换，带轻振动。
 */
class VerticalSwitchView(
    context: Context,
    isOn: Boolean = false,
    private val onToggle: (Boolean) -> Unit
) : View(context) {

    private var state = isOn

    private val orangeFill = Color.parseColor("#FF7A00")
    private val thumbColor = Color.parseColor("#FF7A00")   // 橙色圆点
    private val thumbRing = Color.WHITE                    // 圆点白色描边圈（在药丸里可辨识）

    private val trackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = orangeFill
    }
    private val trackOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = orangeFill
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = thumbColor
    }
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = thumbRing
    }

    private val trackRect = RectF()

    private var vibrator: Vibrator? = null

    // 拒绝弹回动画进度：-1 表示无动画；0~1 表示「拨到开(1)再弹回关(0)」的中间值。
    // 仅 bounceReject 使用，正常切换仍然是瞬时跳变（无动画）。
    private var rejectProgress = -1f
    private var rejectAnimator: ValueAnimator? = null

    init {
        // 关闭默认点击水波纹/按压缩放，控件只做纯状态切换
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_NONE, null)
    }

    /** 外部（root 读回真值）设置状态，不触发回调。 */
    fun setOnSilently(on: Boolean) {
        if (state != on) {
            state = on
            invalidate()
        }
    }

    fun isOn(): Boolean = state

    /** 忙碌时禁用交互。 */
    fun setEnabledState(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.5f
    }

    /**
     * 拒绝开启时的「弹回」动画：拨杆从「临时拨上(开)」弹回「关」。
     * 用于门槛校验不通过（如电量低于阈值）时，给用户明确的视觉拒绝反馈。
     *
     * 关键：前面 flipTo(true) 已乐观地把 state 改成 true，这里必须先把
     * state 恢复到 false，再执行从 1（开）弹回 0（关）的动画，
     * 保证动画结束后拨杆正确停留在「关」。
     */
    fun bounceReject() {
        // 取消上一次未完成的动画
        rejectAnimator?.cancel()

        // 恢复真实状态为「关」（flipTo 之前乐观拨成了 true）
        state = false

        // 从 1（开，用户刚才拨到的位置）弹回 0（关），带一点阻尼感
        rejectProgress = 1f
        rejectAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 200L
            addUpdateListener { a ->
                rejectProgress = a.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                rejectProgress = -1f
                state = false
                invalidate()
            }
        }
        rejectAnimator!!.start()

        // 拒绝带一次轻振动（区别于成功切换）
        vibrate()
    }

    /** ValueAnimator 结束回调的兼容封装（避免直接依赖 android.animation 扩展）。 */
    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f

        // 轨道药丸：上下留一点边距
        val trackW = w * 0.60f
        val trackH = h * 0.94f
        val trackLeft = cx - trackW / 2f
        val trackTop = h * 0.03f
        val trackRight = cx + trackW / 2f
        val trackBottom = trackTop + trackH
        trackRect.set(trackLeft, trackTop, trackRight, trackBottom)
        val radius = trackW / 2f

        // 渲染进度：正常时按 state（0=关，1=开）；拒绝动画时按 rejectProgress 插值
        val progress = if (rejectProgress >= 0f) rejectProgress else if (state) 1f else 0f
        val onFrac = progress.coerceIn(0f, 1f)

        if (onFrac > 0.02f) {
            // 打开（或接近打开）：轨道填满橙色药丸（透明度随 progress 淡化）
            trackFillPaint.alpha = (onFrac * 255).toInt()
            canvas.drawRoundRect(trackRect, radius, radius, trackFillPaint)
            trackFillPaint.alpha = 255
        } else {
            // 关闭：橙色轮廓框（描边，内部透明）
            canvas.drawRoundRect(trackRect, radius, radius, trackOutlinePaint)
        }

        // 圆点（thumb）：橙色圆点，随 progress 在上/下之间移动；外圈白色描边增强辨识
        val thumbD = trackW - dp(6f)
        val thumbR = thumbD / 2f
        val thumbCX = cx
        val cyBottom = trackBottom - thumbR - dp(3f)
        val cyTop = trackTop + thumbR + dp(3f)
        val thumbCY = cyBottom + (cyTop - cyBottom) * onFrac
        canvas.drawCircle(thumbCX, thumbCY, thumbR, thumbPaint)
        canvas.drawCircle(thumbCX, thumbCY, thumbR, thumbRingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.y < height * 0.5f) {
                    // 点上半区 = 想合闸（开）
                    if (!state) flipTo(true)
                } else {
                    // 点下半区 = 想分闸（关）
                    if (state) flipTo(false)
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * 用户拨杆动作：先乐观地把 state 拨到目标位（视觉立即反馈），
     * 再回调 onToggle 让 Activity 执行实际的系统写入。
     * 若被拒绝（如电量低于阈值），Activity 会调用 bounceReject() 弹回。
     */
    private fun flipTo(on: Boolean) {
        state = on
        invalidate()
        vibrate()
        onToggle(on)
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