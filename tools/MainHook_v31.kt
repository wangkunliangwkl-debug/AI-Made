package com.rcb.bypass

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.provider.Settings
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ============================================================
 * 反向无线充电 电量门槛解锁  v3.3
 * ============================================================
 *
 * 目标：恢复「可调档位」语义，让门槛真正由用户控制。
 *
 * 关键逆向结论（ColorOS 16 / com.oplus.battery）：
 *   - f5.f.s1(Context) 读 Settings$System[wireless_reverse_battery_level_threshold]，
 *     返回"最低电量门槛"；u8.y.a0() 里 `电量 < 门槛` 就把开关禁用。
 *   - 因此门槛越小，越容易开反充；门槛越大，越难开。
 *   - 用户想要"低电量也能反充"，本质是"把门槛调低"。
 *
 * 档位语义：滑块 5~95（步进 5，共 19 档）。
 *   门槛=5  -> 电量 >= 5% 即放开
 *   门槛=95 -> 电量 >= 95% 才放开
 *
 * 用户档位存储：
 *   - Settings.Global[rcb_reverse_threshold]  （面板主键，优先）
 *   - Settings.System[wireless_reverse_battery_level_threshold] （系统原生键，同步写）
 *   默认 10%。
 *
 * v1/v2 失败的三处根因（务必避开）：
 *   1) readUserThreshold 末尾 coerceIn(1, 25) 把值夹回 <=25；
 *   2) patchThresholdValue / hookAllComparisonMethods 里硬编码 userPct=10；
 *   3) 比较方向写反：if (pct >= userPct && pct < 25) 是往上抬电量，而不是往下压门槛。
 *
 * v3.1 正确做法：
 *   - 读用户档位时，允许 5..95，不做 25 上限钳制；
 *   - 通过 XposedHelpers.callStaticMethod 调用 Settings 原生方法读值，
 *     用「原始方法句柄」避开自己被 hook 的递归拦截；
 *   - L1/L2 统一返回"用户档位"；
 *   - L3 用真实电量与用户档位比较，>= 则 setEnabled(true)，< 则 setEnabled(false)。
 *
 * 生效范围：仅 com.oplus.battery（含 :ui 子进程）。
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        log("initZygote v3.2 modulePath=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PKG_BATTERY) return
        log("handleLoadPackage ${lpparam.packageName} proc=${lpparam.processName}")
        try {
            hookThresholdGetter(lpparam.classLoader)
        } catch (t: Throwable) {
            log("L1 hookThresholdGetter failed: $t")
        }
        try {
            hookSettingsReads(lpparam.classLoader)
        } catch (t: Throwable) {
            log("L2 hookSettingsReads failed: $t")
        }
        try {
            hookPreferenceFragmentUi(lpparam.classLoader)
        } catch (t: Throwable) {
            log("L3 hookPreferenceFragmentUi failed: $t")
        }
        try {
            hookStateWrites(lpparam.classLoader)
        } catch (t: Throwable) {
            log("L4 hookStateWrites failed: $t")
        }
        try {
            hookBatteryMonitor()
        } catch (t: Throwable) {
            log("L5 hookBatteryMonitor failed: $t")
        }
    }

    // ------------------------------------------------------------------
    // 读取用户档位（供内部判定用，返回 Int）。
    // 读值来源优先 Global[rcb_reverse_threshold]，其次 System[threshold]，
    // 兜底 DEFAULT_PCT，并钳制在 [5, 95]。
    // ------------------------------------------------------------------
    private fun userThreshold(): Int {
        var v = DEFAULT_PCT
        try {
            val cr = systemContext()?.contentResolver
            // 优先读 Global[rcb_reverse_threshold]（面板主键）
            v = Settings.Global.getInt(cr, GLOBAL_KEY, -1)
            if (v < MIN_PCT || v > MAX_PCT) {
                // 回退：读 System 原生键
                v = Settings.System.getInt(cr, KEY_THRESHOLD, DEFAULT_PCT)
            }
        } catch (_: Throwable) {
            v = DEFAULT_PCT
        }
        if (v < MIN_PCT || v > MAX_PCT) {
            v = DEFAULT_PCT
        }
        return v
    }

    /** 取系统 Context（Xposed 模块标准做法，不依赖被注入进程的 app Context）。 */
    private fun systemContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as? Context
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------
    // L1: f5.f 里所有「返回 int 且无参」的方法 -> 返回用户档位
    // ------------------------------------------------------------------
    private fun hookThresholdGetter(cl: ClassLoader) {
        if (s1Hooked) return
        val cls = XposedHelpers.findClassIfExists("f5.f", cl) ?: run {
            log("L1: class f5.f not found")
            return
        }
        var hit = 0
        cls.declaredMethods.forEach { m ->
            try {
                if (m.returnType == Int::class.javaPrimitiveType && m.parameterCount == 0) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = userThreshold()
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = userThreshold()
                        }
                    })
                    hit++
                    log("L1: hooked ${m.name}() -> userThreshold")
                }
            } catch (_: Throwable) {
            }
        }
        s1Hooked = hit > 0
        log("L1: total hooked = $hit")
    }

    // ------------------------------------------------------------------
    // L2: Settings$System / Settings$Global 读取拦截
    // ------------------------------------------------------------------
    private fun hookSettingsReads(cl: ClassLoader) {
        if (settingsHooked) return
        val targetClasses = listOf(
            XposedHelpers.findClassIfExists("android.provider.Settings\$System", cl)
                ?: XposedHelpers.findClassIfExists("android.provider.Settings\$System", null),
            XposedHelpers.findClassIfExists("android.provider.Settings\$Global", cl)
                ?: XposedHelpers.findClassIfExists("android.provider.Settings\$Global", null)
        ).filterNotNull()

        if (targetClasses.isEmpty()) {
            log("L2: Settings\$System/Global not found")
            return
        }

        val names = arrayOf("getInt", "getIntForUser", "getLong", "getLongForUser")
        var hit = 0
        for (sysCls in targetClasses) {
            sysCls.declaredMethods.forEach { m ->
                if (m.name !in names) return@forEach
                if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) return@forEach
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val key = param.args.firstOrNull {
                                it is String && it == KEY_THRESHOLD
                            } as? String ?: return
                            if (key != KEY_THRESHOLD) return
                            val u = userThreshold()
                            param.result = when (m.returnType) {
                                Int::class.javaPrimitiveType, Int::class.java -> u
                                Long::class.javaPrimitiveType, Long::class.java -> u.toLong()
                                else -> u
                            }
                        }
                    })
                    hit++
                } catch (_: Throwable) {
                }
            }
        }
        settingsHooked = hit > 0
        log("L2: total hooked = $hit")
    }

    // ------------------------------------------------------------------
    // L3: u8.y 界面控制（按真实电量与档位判定）
    // ------------------------------------------------------------------
    private fun hookPreferenceFragmentUi(cl: ClassLoader) {
        if (uiHooked) return
        val cls = XposedHelpers.findClassIfExists("u8.y", cl) ?: run {
            log("L3: class u8.y not found")
            return
        }
        var hit = 0
        cls.declaredMethods.forEach { m ->
            if (m.parameterCount > 2) return@forEach
            val n = m.name
            if (n != "a0" && n != "W" && n != "X" && n != "Y" &&
                n != "onResume" && n != "c0" && n != "b0"
            ) return@forEach
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            applyUiState(param.thisObject)
                        } catch (_: Throwable) {
                        }
                    }
                })
                hit++
                log("L3: hooked $n")
            } catch (_: Throwable) {
            }
        }
        uiHooked = hit > 0
        log("L3: total hooked = $hit")
    }

    // ------------------------------------------------------------------
    // L4: 拦截对 wireless_reverse_charging_state 键的「写入」，实现门槛硬约束。
    //
    // 背景：写 state 键（面板 / 系统 tile / 任何入口）会直接触发 enable_tx，
    //      完全绕过了系统在设置页做的「电量 < 门槛就禁用」判定。
    //      所以要在这里补一道校验：尝试写 1（开启）时，电量低于档位就强制改写成 0。
    // ------------------------------------------------------------------
    private fun hookStateWrites(cl: ClassLoader) {
        if (stateWritesHooked) return
        val sysCls = XposedHelpers.findClassIfExists("android.provider.Settings\$System", cl)
            ?: XposedHelpers.findClassIfExists("android.provider.Settings\$System", null)
        if (sysCls == null) {
            log("L4: Settings\$System not found")
            return
        }
        val names = arrayOf("putInt", "putIntForUser", "putString", "putStringForUser")
        var hit = 0
        for (m in sysCls.declaredMethods) {
            if (m.name !in names) continue
            if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val key = param.args.firstOrNull { it is String && it == KEY_STATE } as? String
                                ?: return
                            // 目标想要写进去的值（第 2 个参数）
                            val wantOn = when (val v = param.args.getOrNull(1)) {
                                is Number -> v.toInt() == 1
                                is String -> v == "1" || v.equals("true", true) || v.equals("enable", true)
                                is Boolean -> v
                                else -> null
                            } ?: return
                            if (!wantOn) return  // 只有「开启」才需要校验，关闭不拦
                            val batt = batteryPct()
                            val th = userThreshold()
                            if (batt < th) {
                                log("L4: refuse enable, batt=$batt < threshold=$th")
                                // 把写入值改写成「关闭」（0/false/"0"）
                                param.args[1] = when (param.args[1]) {
                                    is String -> "0"
                                    is Long -> 0L
                                    is Boolean -> false
                                    else -> 0
                                }
                            }
                        } catch (t: Throwable) {
                            log("L4 before err: $t")
                        }
                    }
                })
                hit++
            } catch (_: Throwable) {
            }
        }
        stateWritesHooked = hit > 0
        log("L4: total hooked = $hit")
    }

    // ------------------------------------------------------------------
    // L5: 「开着时电量掉到门槛以下 → 自动断开」兜底监听。
    //
    // L4 只在「写 state=1 的瞬间」校验电量，已经开着的反充不会因电量
    // 下降而被拦截。所以这里再挂一个 ACTION_BATTERY_CHANGED 接收器：
    // 每次系统广播电量变化，就检查「当前是否在反充」+「电量是否 < 门槛」，
    // 满足则写 state=0 自动关闭。这样用户不用手动关注，掉到门槛即停。
    // ------------------------------------------------------------------
    private fun hookBatteryMonitor() {
        if (batteryMonitorRegistered) return
        val ctx = systemContext() ?: run {
            log("L5: systemContext null, skip")
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    autoStopBelowThreshold()
                } catch (t: Throwable) {
                    log("L5 onReceive err: $t")
                }
            }
        }
        try {
            ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryMonitorRegistered = true
            log("L5: battery monitor registered")
        } catch (t: Throwable) {
            log("L5: register failed: $t")
        }
    }

    /** 若反充当前开着、且电量 < 门槛，则写 state=0 关闭。 */
    private fun autoStopBelowThreshold() {
        val ctx = systemContext() ?: return
        // 只在反充「开着」时才可能断开；已关则无需处理
        val on = try {
            Settings.System.getInt(ctx.contentResolver, KEY_STATE, 0) == 1
        } catch (_: Throwable) {
            false
        }
        if (!on) return

        val batt = batteryPct()
        val th = userThreshold()
        if (batt < th) {
            log("L5: auto-stop, batt=$batt < threshold=$th, writing state=0")
            try {
                Settings.System.putInt(ctx.contentResolver, KEY_STATE, 0)
            } catch (_: Throwable) {
                // fallback root
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put system $KEY_STATE 0"))
                } catch (t: Throwable) {
                    log("L5: root fallback failed: $t")
                }
            }
        }
    }

    /** 用系统 Context 读当前电量（%）。失败返回 100（不误拦）。 */
    private fun batteryPct(): Int = try {
        val ctx = systemContext()
        val bm = ctx?.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    } catch (_: Throwable) {
        100
    }

    /**
     * 依据「当前电量 vs 用户档位」决定开关可用性。
     * 电量 >= 档位：setEnabled(true) + 清掉低电量摘要
     * 电量 <  档位：setEnabled(false)（尊重用户设定的门槛）
     */
    private fun applyUiState(host: Any?) {
        if (host == null) return
        try {
            val sw = XposedHelpers.getObjectField(host, "e") as? android.preference.Preference
            if (sw != null) {
                val ctx = sw.context
                if (ctx != null) {
                    val batt = batteryLevel(ctx)
                    val th = userThreshold()
                    val shouldEnable = batt >= th
                    if (shouldEnable && !sw.isEnabled) {
                        sw.isEnabled = true
                        log("L3: setEnabled(true) batt=$batt >= th=$th")
                    } else if (!shouldEnable && sw.isEnabled) {
                        // 尊重门槛：低于档位则禁用
                        sw.isEnabled = false
                        log("L3: setEnabled(false) batt=$batt < th=$th")
                    }
                }
                // 摘要处理：清掉/恢复低电量提示
                try {
                    val r = sw.context?.resources ?: return
                    val below = getStringSafe(r, "battLevel_below_threshold_oncreate")
                    if (below != null && sw.summary?.toString() == below) {
                        sw.summary = if (batteryLevel(sw.context!!) >= userThreshold()) null else below
                        log("L3: summary sync done")
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        // 通过 Assignment 恢复
        try {
            val m = XposedHelpers.getIntField(host, "m")
            if (m != 0) {
                val c0 = host.javaClass.declaredMethods.firstOrNull { it.name == "c0" }
                if (c0 != null) {
                    c0.invoke(host)
                    log("L3: re-invoked c0() to restore assignment")
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 读当前电量（%）。失败返回 100，避免误禁。 */
    private fun batteryLevel(ctx: Context): Int = try {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        if (bm != null) {
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else 100
    } catch (_: Throwable) {
        100
    }

    private fun getStringSafe(r: Resources, name: String): String? = try {
        val id = r.getIdentifier(name, "string", "com.oplus.battery")
        if (id != 0) r.getString(id) else null
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val TAG = "RcbBypass"
        const val PKG_BATTERY = "com.oplus.battery"
        const val KEY_THRESHOLD = "wireless_reverse_battery_level_threshold"
        const val KEY_STATE = "wireless_reverse_charging_state"
        const val GLOBAL_KEY = "rcb_reverse_threshold"
        const val DEFAULT_PCT = 10
        const val MIN_PCT = 5
        const val MAX_PCT = 95

        @Volatile private var s1Hooked = false
        @Volatile private var settingsHooked = false
        @Volatile private var uiHooked = false
        @Volatile private var stateWritesHooked = false
        @Volatile private var batteryMonitorRegistered = false

        fun log(msg: String) {
            XposedBridge.log("$TAG: $msg")
        }
    }
}
