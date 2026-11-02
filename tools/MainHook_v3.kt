package com.rcb.bypass

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ============================================================
 *  反向无线充电 电量门槛绕过  v3.0
 * ============================================================
 *
 * 逆向取证结论（ColorOS 16 / com.oplus.battery = /system_ext/app/Battery/Battery.apk）：
 *
 * 1) 阈值读取：f5.f.s1()
 *      Settings$System.getIntForUser(resolver, "wireless_reverse_battery_level_threshold", 25, 1)
 *    -> 25 只是默认值，返回值就是用户设定的"最低电量门槛"。
 *
 * 2) 阈值写入：f5.f.j4()  (界面拖动档位时调用)
 *      putIntForUser(resolver, "wireless_reverse_battery_level_threshold", v, 1)
 *
 * 3) 阈值监听：q8.a.i()  registerContentObserver(getUriFor(...))
 *
 * 4) 界面禁用判定：u8.y.a0()
 *      fld u8/y;->f -> invoke f5.f.s1() -> if_35 (当前电量 < 门槛)
 *      -> const-string "battLevel_below_threshold_oncreate"
 *      -> fld u8/y;->e -> Preference.setEnabled(false)
 *      -> setSummaryOff(getString(2131820688))
 *    另有 "battery_level_toolow_disable_pref" 分支同样禁用开关。
 *
 *  => 结论：档位只是"最低电量门槛"，电量 20% 而门槛 45% 时开关必然被禁用。
 *     把档位调高只会更容易被禁用，方向是反的。
 *
 * v1/v2 失败原因：
 *    - readUserThreshold 先读 Settings.Global 再读 System，Context 为 null 时回退默认 10；
 *    - `if (user >= orig) return`：用户值大于原值就完全不介入；
 *    - `if (r >= STOCK_THRESHOLD)`：只处理 >= 25 的读取。
 *    这三点导致设 40/60 时 hook 直接失效。
 *
 * v3.0 方案：三层绕过，不再"调阈值"，而是让门槛恒为 0（电量 >= 0 恒成立）。
 *    L1  hook f5.f.s1()            -> 返回 0
 *    L2  hook Settings$System.getIntForUser / getInt / getString
 *                                   -> 读该键时返回 0
 *    L3  hook u8.y.a0() 及其同族 UI 刷新方法
 *                                   -> 执行后强制 setEnabled(true) 并清掉低电量摘要
 *
 * 生效范围：仅 com.oplus.battery（含 :ui 子进程）。
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        log("initZygote v3.0 modulePath=${startupParam.modulePath}")
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
    }

    // ------------------------------------------------------------
    // L1: f5.f.s1() -> 恒 0
    // ------------------------------------------------------------
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
                            param.result = FORCE_THRESHOLD
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = FORCE_THRESHOLD
                        }
                    })
                    hit++
                    log("L1: hooked ${m.name}() -> $FORCE_THRESHOLD")
                }
            } catch (_: Throwable) {
            }
        }
        s1Hooked = hit > 0
        log("L1: total hooked = $hit")
    }

    // ------------------------------------------------------------
    // L2: Settings$System 读取拦截
    // ------------------------------------------------------------
    private fun hookSettingsReads(cl: ClassLoader) {
        if (settingsHooked) return
        val sysCls = XposedHelpers.findClassIfExists("android.provider.Settings\$System", cl)
            ?: XposedHelpers.findClassIfExists("android.provider.Settings\$System", null)
        if (sysCls == null) {
            log("L2: Settings\$System not found")
            return
        }
        var hit = 0
        val names = arrayOf("getInt", "getIntForUser", "getString", "getStringForUser", "getLong")
        sysCls.declaredMethods.forEach { m ->
            if (m.name !in names) return@forEach
            if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) return@forEach
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args.firstOrNull { it is String && it == KEY_THRESHOLD } as? String
                            ?: return
                        if (key != KEY_THRESHOLD) return
                        param.result = when (m.returnType) {
                            Int::class.javaPrimitiveType, Int::class.java -> FORCE_THRESHOLD
                            Long::class.javaPrimitiveType, Long::class.java -> FORCE_THRESHOLD.toLong()
                            String::class.java -> FORCE_THRESHOLD.toString()
                            else -> FORCE_THRESHOLD
                        }
                    }
                })
                hit++
            } catch (_: Throwable) {
            }
        }
        settingsHooked = hit > 0
        log("L2: total hooked = $hit")
    }

    // ------------------------------------------------------------
    // L3: u8.y 界面控制
    // ------------------------------------------------------------
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
                            forceEnableSwitch(param.thisObject)
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

    /**
     * 把 u8.y 里的开关 Preference (字段 e) 强制置为可用，
     * 并清掉"电量过低"类的摘要文案。
     */
    private fun forceEnableSwitch(host: Any?) {
        if (host == null) return
        try {
            val sw = XposedHelpers.getObjectField(host, "e") as? android.preference.Preference
            if (sw != null) {
                if (!sw.isEnabled) {
                    sw.isEnabled = true
                    log("L3: setEnabled(true) on preference key=${sw.key}")
                }
                // 清摘要：低电量提示
                try {
                    val r: Resources = sw.context?.resources ?: return
                    val onText = getStringSafe(r, "battLevel_below_threshold_oncreate")
                    if (onText != null && sw.summary?.toString() == onText) {
                        sw.summary = null
                        log("L3: cleared below-threshold summary")
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        // 同时尝试通过 Assignment 恢复
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
        const val KEY_SWITCH = "wireless_reverse_charging_switch"
        const val FORCE_THRESHOLD = 0

        @Volatile private var s1Hooked = false
        @Volatile private var settingsHooked = false
        @Volatile private var uiHooked = false

        fun log(msg: String) {
            XposedBridge.log("$TAG: $msg")
        }
    }
}
