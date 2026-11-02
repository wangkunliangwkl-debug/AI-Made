package com.rcb.bypass

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.InputStream

/**
 * v3.5：长按状态栏磁贴弹出的「底部小窗」，仿 AirPods 弹窗。
 *
 * 布局：贴屏幕底部、水平居中，三控件一排 —— 左「应用」、中「圆形指针旋钮」、
 * 右「iOS 风格开关」。指针旋钮上下滑动拨档并带振动反馈，点窗口外关闭。
 */
class RcbPanelActivity : Activity() {

    companion object {
        private val RESTART_TARGETS = listOf(
            "com.oplus.battery",
            "com.oplus.athena",
            "com.oplus.safecenter",
            "com.oplus.wirelesssettings"
        )

        const val GLOBAL_KEY = "rcb_reverse_threshold"
        const val SYS_KEY = "wireless_reverse_battery_level_threshold"
        const val DEFAULT_PCT = 10

        /**
         * 可调档位，和 SettingsActivity 保持一致。
         * v1.9：范围放宽到 5%~95%，步进 5%。
         * 下限 5% 是留给系统保底关机余量的；上限 95% 不封顶到 100，
         * 因为电池接近满电时对外放电的温升和损耗都不可控。
         */
        val STEPS = (5..95 step 5).toList()

        fun readThreshold(ctx: Context): Int = try {
            Settings.Global.getInt(ctx.contentResolver, GLOBAL_KEY, DEFAULT_PCT)
        } catch (_: Throwable) {
            DEFAULT_PCT
        }

        /**
         * v3.2：反向充电真正的总开关 = Settings.System[wireless_reverse_charging_state]。
         *
         * 实测（ColorOS 16）确认因果链：
         *   写 state=1  -> 系统监听器自动把 /proc/wireless/enable_tx 置为 enable
         *   写 state=0  -> enable_tx 自动回 disable
         * 即 state 键是「上游总开关」，/proc/wireless/enable_tx 是「下游硬件使能」。
         *
         * v1.8 时代面板直接写 /proc/wireless/enable_tx（下游），绕过了 state 键，
         * 导致：
         *   1) 系统设置页反充开关不随之更新（开关不联动）；
         *   2) 阈值 hook（f5.f.s1 / u8.y.a0）只作用于系统开关链路，
         *      管不住直接写 enable_tx 的旁路（限制不联动）。
         *
         * v3.2 修复：开关统一写 state 键（0/1），让系统状态机自动同步，
         * 这样面板开关、系统设置页、状态栏磁贴、门槛判定全部联动。
         */
        const val STATE_KEY = "wireless_reverse_charging_state"

        /** 读当前反充开关状态（1=开，0=关，读失败按关）。 */
        fun readTxEnabled(ctx: Context): Boolean = try {
            Settings.System.getInt(ctx.contentResolver, STATE_KEY, 0) == 1
        } catch (_: Throwable) {
            false
        }

        /** 写反充开关。优先走 root，避免普通应用写 System 表被拒。 */
        fun writeTxEnabled(on: Boolean, ctx: Context, out: StringBuilder): Boolean {
            val v = if (on) 1 else 0
            // 先试直接写（若有 WRITE_SETTINGS 授权）
            var directOk = false
            try {
                Settings.System.putInt(ctx.contentResolver, STATE_KEY, v)
                directOk = true
            } catch (_: Throwable) {
            }
            if (directOk) return true
            // 回退 root
            return try {
                val p = ProcessBuilder("su", "-c", "settings put system $STATE_KEY $v")
                    .redirectErrorStream(true).start()
                val txt = p.inputStream.bufferedReader().readText()
                val code = p.waitFor()
                out.append(txt)
                code == 0 && readTxEnabled(ctx) == on
            } catch (t: Throwable) {
                out.append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
                false
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private lateinit var knob: KnobView
    private lateinit var actionBtn: Button
    private lateinit var powerSwitch: VerticalSwitchView
    private lateinit var toastText: TextView
    private var pendingPct = DEFAULT_PCT
    private var busy = false
    private var txOn = false
    private var txBusy = false
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 底部弹窗：贴在屏幕底部、水平居中，像 AirPods 弹窗那样从下方浮出。
        // 半透明深色遮罩铺满全屏，点卡片外即关闭。
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setDimAmount(0.35f)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)

        pendingPct = readThreshold(this)
        if (pendingPct !in STEPS) pendingPct = DEFAULT_PCT

        setContentView(buildPanel())

        syncPower()
    }

    /**
     * v1.8：把 /proc 里的真实状态同步到 UI。
     *
     * 读 /proc 得走 su，会阻塞，所以放后台线程；
     * 用 syncing 防止连续 onResume 时起一堆线程。
     */
    private fun syncPower() {
        if (syncing) return
        syncing = true
        Thread {
            val on = readTxEnabled(this)
            main.post {
                syncing = false
                // 用户可能在这期间自己点了开关，别拿旧读数覆盖新状态
                if (!txBusy) {
                    txOn = on
                    renderPower()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // 小窗可能被反复打开，每次回到前台都对一次真值，
        // 免得系统那边（比如电量低于阈值自动停）变了、这边还显示旧的
        if (::powerSwitch.isInitialized) syncPower()
    }

    /** 把刀闸开关刷成当前状态（合=开，分=关，不触发回调）。 */
    private fun renderPower() {
        if (!::powerSwitch.isInitialized) return
        powerSwitch.setOnSilently(txOn)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 轻振动反馈（按钮点击、开关切换等）。 */
    private fun vibrate() {
        try {
            val vm = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vm?.vibrate(
                android.os.VibrationEffect.createOneShot(
                    16, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * 统一提示：写到卡片底部的常驻提示框（白色线框区域）。
     * 不再用系统 Toast，也不自动消失，提示内容常驻显示。
     */
    private fun toast(msg: String) {
        if (!::toastText.isInitialized) return
        toastText.text = msg
    }

    private fun buildPanel(): View {
        // 底部卡片：贴底、圆角在上、半透明白 + 毛玻璃感
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(28).toFloat(), dp(28).toFloat(),
                    dp(28).toFloat(), dp(28).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#F2FFFFFF"))
            }
            elevation = dp(12).toFloat()
            // 卡片本身也不要有按压缩放动画（去掉 Material 默认 stateListAnimator）
            stateListAnimator = null
        }

        // 主行：左「应用阈值」｜中「指针圆盘」｜右「竖排开关」
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 左：应用阈值按钮（分两行，禁用 ripple 果冻动画）
        actionBtn = Button(this).apply {
            text = "应用\n阈值"
            textSize = 15f
            isAllCaps = false
            setPadding(dp(20), dp(10), dp(20), dp(10))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.0f)
            // 文字颜色：正常=白，按下=橙（与填充反色）
            setTextColor(
                android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf()
                    ),
                    intArrayOf(
                        Color.parseColor("#FF7A00"),
                        Color.WHITE
                    )
                )
            )
            // 背景：正常=橙色填充+橙色边框；按下=反色（白填充+橙色边框）
            background = android.graphics.drawable.StateListDrawable().apply {
                val orange = Color.parseColor("#FF7A00")
                // 按下态：白底 + 橙边框
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply {
                        cornerRadius = dp(24).toFloat()
                        setColor(Color.WHITE)
                        setStroke(dp(2), orange)
                    }
                )
                // 正常态：橙底 + 橙边框
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        cornerRadius = dp(24).toFloat()
                        setColor(orange)
                        setStroke(dp(2), orange)
                    }
                )
            }
            // 关闭水波纹与默认前景动效
            stateListAnimator = null
            setOnClickListener {
                vibrate()
                apply()
            }
        }
        row.addView(actionBtn, LinearLayout.LayoutParams(dp(92), dp(64)).apply {
            rightMargin = dp(10)
        })

        // 中：圆形指针控件（占中间大部分宽度）
        knob = KnobView(this, STEPS) { pct ->
            pendingPct = pct
        }
        knob.setValue(pendingPct)
        row.addView(knob, LinearLayout.LayoutParams(0, dp(190), 1f).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
        })

        // 右：竖向 iOS 开关（朝上=开，朝下=关，无过渡动画）
        powerSwitch = VerticalSwitchView(this, txOn) { on ->
            if (!txBusy) togglePower()
        }
        row.addView(powerSwitch, LinearLayout.LayoutParams(dp(56), dp(150)).apply {
            leftMargin = dp(10)
        })

        card.addView(row, LinearLayout.LayoutParams(-1, -2))

        // 底部提示框：白色线框 + 橙色字体，常驻显示（始终可见）。
        // 常驻两行高度（minLines=2），单行文案时也占两行空间，避免显示两行时发生跳动。
        toastText = TextView(this).apply {
            text = "就绪"
            textSize = 14f
            setTextColor(Color.parseColor("#FF7A00"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minLines = 2
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(1), Color.WHITE)
            }
            visibility = View.VISIBLE
        }
        card.addView(toastText, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(0)
        })

        return card
    }

    /** v3.2：开关反充。开启前先校验「电量 >= 档位」，低于档位则拒绝并提示。 */
    private fun togglePower() {
        if (txBusy) return
        val target = !txOn

        // 开启前校验门槛（与 hook 层 L4 一致，这里只为给用户友好提示）
        if (target) {
            val batt = batteryPct()
            val th = readThreshold(this)
            if (batt < th) {
                toast("当前电量 $batt% 低于门槛 $th%\n暂不能开启反向充电")
                // 拨杆弹回：短暂拨到「开」再弹回「关」，给用户被拒绝的视觉反馈
                powerSwitch.bounceReject()
                return
            }
        }

        txBusy = true
        powerSwitch.setEnabledState(false)

        Thread {
            val out = StringBuilder()
            val ok = writeTxEnabled(target, this, out)

            main.post {
                txBusy = false
                powerSwitch.setEnabledState(true)
                if (ok) {
                    txOn = target
                    renderPower()
                    toast(if (target) "反向充电已开启，把设备贴到手机背面即可" else "反向充电已关闭")
                } else {
                    renderPower()
                    val why = if (out.contains("denied", true) || out.contains("Permission", true))
                        "权限被拒，请确认已授予 Root"
                    else "开关未生效，请重试"
                    toast(why)
                }
            }
        }.start()
    }

    /** 读当前电量（%）。失败返回 100（不误拦）。 */
    private fun batteryPct(): Int = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    } catch (_: Throwable) {
        100
    }

    /** 写入阈值 → 重启相关进程 → 关窗。全部在后台线程，避免卡 UI。 */
    private fun apply() {
        if (busy) return
        busy = true
        val pct = pendingPct
        actionBtn.isEnabled = false
        actionBtn.text = "应用\n中…"

        Thread {
            val out = StringBuilder()

            // 第一步：写阈值。写没写进去，独立判定，不再被重启失败掩盖。
            val wrote = try {
                val a = Settings.Global.putInt(contentResolver, GLOBAL_KEY, pct)
                var b = false
                try {
                    b = Settings.System.putInt(contentResolver, SYS_KEY, pct)
                } catch (_: Throwable) {
                }
                a || b || readThreshold(this) == pct
            } catch (_: Throwable) {
                false
            }

            // 第二步：只杀进程，不拉起任何界面。
            // v1.7：之前用 am start 拉起 SettingsActivity 会让用户看到一个纯白页，
            // 而阈值在写键那一刻就已生效，进程被杀掉后 hook 会重走一遍，
            // 无需界面配合。即开即走。
            val stopped = runAsRoot(
                "am force-stop " + RESTART_TARGETS.joinToString(" "), out
            )

            main.post {
                busy = false
                actionBtn.isEnabled = true
                actionBtn.text = "应用\n阈值"
                val msg = when {
                    !wrote -> "阈值写入失败，请检查权限"
                    stopped -> "阈值已设为 $pct%，已生效"
                    else -> "阈值已设为 $pct%，但进程未重启，需手动重启一次"
                }
                toast(msg)
                // v3.3：设完阈值不再关窗，面板留在原地，仅点「关闭」或点窗口外才退出
            }
        }.start()
    }

    private fun runAsRoot(cmd: String, sink: StringBuilder): Boolean {
        var proc: Process? = null
        return try {
            proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val t1 = Thread { drain(proc!!.inputStream, outBuf) }
            val t2 = Thread { drain(proc!!.errorStream, errBuf) }
            t1.start()
            t2.start()
            val code = proc.waitFor()
            t1.join(3000)
            t2.join(3000)
            sink.append(outBuf).append(errBuf)
            code == 0
        } catch (t: Throwable) {
            sink.append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
            false
        } finally {
            try {
                proc?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun drain(s: InputStream, sb: StringBuilder) {
        try {
            val b = ByteArray(1024)
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                sb.append(String(b, 0, n))
            }
        } catch (_: Throwable) {
        }
    }
}
