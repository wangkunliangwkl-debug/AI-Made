package com.rcb.bypass

import android.content.Context
import android.os.BatteryManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 反向无线充电低电量阈值解锁
 *
 * 原理：
 *   ColorOS 在「反向无线充电」开启前会检查当前电量百分比是否 >= 25。
 *   这个检查散落在若干个类里，且不同 ColorOS 版本实现位置会变。
 *   因此本模块采用「多层兜底」策略，不依赖单一类名：
 *
 *   L1  Settings.Global / SystemProperties 读取 hook
 *       —— 某些版本阈值是配置项，改读取返回值最稳。
 *   L2  方法返回值 hook —— 扫描并识别「返回 25 / 25000 之类常量」的方法，
 *       以及参数含电量且与 25 做比较的方法，改写比较结果。
 *   L3  已知类名精确 hook
 *       —— 命中 ColorOS 常见实现则直接替换，最精准。
 *   L4  Settings 页面 UI 兜底
 *       —— 设置里灰掉/禁用的开关，强行使其可用。
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    /** 缓存一个 application context，供无法直接拿到 Context 的场景使用 */
    @Volatile
    private var appContext: Context? = null

    companion object {
        private const val TAG = "RCBypass"

        /** 原厂阈值 25% */
        private const val STOCK_THRESHOLD = 25

        /** 是否已初始化 */
        @Volatile
        private var inited = false

        /** 系统真实读取的设置键（ColorOS / Settings.System） */
        private const val SYS_KEY = "wireless_reverse_battery_level_threshold"

        /** 兜底读取 SystemProperties 的键（部分版本用 prop 控制） */
        private const val PROP_KEY = "persist.sys.rcb.reverse_threshold"

        /** 用户阈值上限。v2.0：放开到 100，不再夹到 25，否则 5~95 档位全部失效 */
        private const val MAX_THRESHOLD = 100

        /** 默认阈值 */
        private const val DEFAULT_THRESHOLD = 10

        /**
         * v2.0：用户设定阈值的进程内缓存。
         *
         * hook 回调里没有 Context，读不了 Settings，所以必须在能拿到
         * Context 的地方读一次存起来，三处用到阈值的地方统一读这个缓存。
         */
        @Volatile
        private var cachedThreshold: Int = DEFAULT_THRESHOLD

        /**
         * v2.0：静态持有 Context，供 companion 内部方法使用。
         * 外部类的 appContext 是实例属性，companion 里访问不到。
         */
        @Volatile
        private var staticContext: Context? = null

        private fun appContextInstance(): Context? = staticContext

        private fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
        }

        /**
         * 读取用户设置的阈值（1~100），默认 10。
         *
         * v2.0 修复：原来这里 coerceIn(1, STOCK_THRESHOLD) 把上限夹在 25，
         * 导致界面选 30~95 全部被压回 25，档位形同虚设。现在放宽到 1~100。
         *
         * 读到的值同时写入 cachedThreshold，供没有 Context 的 hook 回调使用。
         */
        private fun readUserThreshold(ctx: Context?): Int {
            if (ctx == null) return cachedThreshold
            val v = try {
                val cr = ctx.contentResolver
                var x = android.provider.Settings.Global.getInt(cr, "rcb_reverse_threshold", -1)
                if (x <= 0) {
                    x = android.provider.Settings.System.getInt(cr, SYS_KEY, DEFAULT_THRESHOLD)
                }
                x
            } catch (_: Throwable) {
                DEFAULT_THRESHOLD
            }
            val clamped = v.coerceIn(1, MAX_THRESHOLD)
            cachedThreshold = clamped
            return clamped
        }

        /**
         * v2.0：refresh 一次缓存。拿不到 Context 时保持旧值，不抛异常。
         */
        private fun refreshThresholdCache(ctx: Context?) {
            if (ctx == null) return
            try {
                readUserThreshold(ctx)
                log("threshold cache = $cachedThreshold")
            } catch (_: Throwable) {
            }
        }

        /** 取当前生效阈值，供 hook 回调使用 */
        private fun currentThreshold(): Int = cachedThreshold.coerceIn(1, MAX_THRESHOLD)

        /** 上次重读缓存的时间戳，用于节流 */
        @Volatile
        private var lastRefreshMs: Long = 0L

        /**
         * v2.0：节流重读。hook 回调里调用，最多每 1.5 秒查一次 Settings，
         * 这样用户改完档位不必重启系统进程即可生效。
         */
        private fun currentThresholdFresh(): Int {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastRefreshMs > 1500L) {
                lastRefreshMs = now
                val c = appContextInstance()
                if (c != null) refreshThresholdCache(c)
            }
            return currentThreshold()
        }
    }

    /**
     * v2.0：在任何 hook 生效前，先把用户阈值读进缓存。
     *
     * 为什么需要它：hook 回调（afterHookedMethod / beforeHookedMethod）里拿不到
     * Context，读不了 Settings。所以必须在进程加载早期读一次并缓存。
     *
     * 取 Context 的三级兜底：
     *   1. 自己缓存的 appContext（模块界面/自身进程）
     *   2. ActivityThread.currentApplication()——普通应用进程可用
     *   3. 反射 ActivityThread.getSystemContext()——system_server 里能拿到系统上下文
     */
    private fun bootstrapThresholdCache() {
        try {
            var ctx: Context? = appContext
            if (ctx == null) {
                ctx = try {
                    val at = Class.forName("android.app.ActivityThread")
                    val cur = at.getMethod("currentApplication").invoke(null) as? Context
                    cur
                } catch (_: Throwable) {
                    null
                }
            }
            if (ctx == null) {
                ctx = try {
                    val at = Class.forName("android.app.ActivityThread")
                    at.getMethod("getSystemContext").invoke(null) as? Context
                } catch (_: Throwable) {
                    null
                }
            }
            if (ctx == null) {
                log("bootstrap: no context, keep default ${cachedThreshold}")
                return
            }
            appContext = ctx
            staticContext = ctx
            readUserThreshold(ctx)
            log("bootstrap threshold = $cachedThreshold")
        } catch (t: Throwable) {
            log("bootstrap failed: ${t.message}")
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        log("Zygote init, module installed")
    }

    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        // v2.0：进入任何目标进程时，先想办法把用户阈值读进缓存。
        // 拿不到 Context 就用 android 包的系统上下文兜底；都失败则保持默认值。
        bootstrapThresholdCache()
        when (lp.packageName) {
            "android" -> hookSystemServer(lp)
            "com.android.settings" -> hookSettings(lp)
            "com.oplus.battery" -> hookOplusBattery(lp)
            "com.oplus.athena" -> hookOplusAthena(lp)
            "com.oplus.safecenter" -> hookSafeCenter(lp)
            "com.oplus.wirelesssettings" -> hookOplusWireless(lp)
            else -> {
                // 其他进程（如 com.android.phone / com.oplus.engineermode）也尝试通用 hook
                if (lp.packageName.startsWith("com.oplus") || lp.packageName == "com.android.systemui") {
                    genericHook(lp)
                }
            }
        }
    }

    // =========================================================
    // L1 + L2 + L3：system_server（android 包）
    // =========================================================
    private fun hookSystemServer(lp: XC_LoadPackage.LoadPackageParam) {
        if (lp.processName != "android") return
        log("Hooking system_server")

        // ---- L3: 精确类名（ColorOS / AOSP 常见实现） ----
        preciseHooks(lp.classLoader)

        // ---- L2: BatteryService / 相关判断 ----
        hookBatteryService(lp.classLoader)

        // ---- L1: 配置读取兜底 ----
        hookConfigReaders(lp.classLoader)
    }

    /**
     * 精确 hook 已知的类。使用 XposedHelpers.findAndHookMethod 的
     * 类名列表 + ClassNotFound 容错，保证版本差异下不崩溃。
     */
    private fun preciseHooks(cl: ClassLoader) {
        // ---------- AOSP / ColorOS 无线充电管理 ----------
        val chargingClasses = listOf(
            "com.android.server.wireless.WirelessChargingService",
            "com.android.server.wireless.WirelessChargingController",
            "com.android.server.power.WirelessChargingManager",
            "com.android.server.power.WirelessReverseChargingManager",
            "com.oplus.server.wireless.OplusWirelessChargingService",
            "com.oplus.server.power.OplusReverseChargingHelper",
            "com.android.server.BatteryService",
            "com.android.server.power.PowerManagerService",
            "com.android.server.power.batterysaver.BatterySaverController"
        )

        for (cn in chargingClasses) {
            try {
                val cls = XposedHelpers.findClass(cn, cl)
                hookAllComparisonMethods(cls, cn)
                log("Found class: $cn")
            } catch (_: Throwable) {
                // 该类不存在，正常
            }
        }

        // ---------- 明确是「阈值 getter」的方法名，直接改返回值 ----------
        val thresholdGetterNames = listOf(
            "getReverseChargingMinLevel",
            "getReverseChargeMinLevel",
            "getReverseChargingThreshold",
            "getReverseChargingLowBatteryThreshold",
            "getMinBatteryLevelForReverseCharging",
            "getWirelessReverseChargingMinBattery",
            "getReverseWirelessChargingMinBatteryLevel",
            "getReverseChargingBatteryLimit",
            "getLowBatteryLimit",
            "getReverseChargingLowerLimit"
        )
        val knownClasses = listOf(
            "com.android.server.wireless.WirelessChargingService",
            "com.android.server.BatteryService",
            "com.oplus.server.wireless.OplusWirelessChargingService",
            "com.android.server.power.PowerManagerService"
        )
        for (cn in knownClasses) {
            try {
                val cls = XposedHelpers.findClass(cn, cl)
                for (m in cls.declaredMethods) {
                    if (m.name in thresholdGetterNames && m.parameterCount <= 1) {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val orig = param.result
                                param.result = patchThresholdValue(orig)
                                log("patched getter ${m.name}: $orig -> ${param.result}")
                            }
                        })
                        log("Hooked threshold getter: $cn#${m.name}")
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 把一个「阈值」类型的返回值，从 25 换成用户设定值。
     * 兼容 int / long / 0~100 / 0~1000 / 0~10000 等不同标度。
     */
    /**
     * 把一个「阈值」类型的返回值，从原厂 25 换成用户设定值。
     * 兼容 int / long / 0~100 / 0~1000 / 0~10000 等不同标度。
     *
     * v2.0 修复：原来这里写死 val userPct = 10，导致所有 getter 被改写成 10%，
     * 与界面选择的 5~95 档位完全无关。现在改为读 cachedThreshold。
     */
    private fun patchThresholdValue(orig: Any?): Any? {
        val userPct = currentThreshold()
        if (userPct == STOCK_THRESHOLD) return orig
        val scale = when (orig) {
            is Int -> when {
                orig == STOCK_THRESHOLD -> 1
                orig == STOCK_THRESHOLD * 10 -> 10
                orig == STOCK_THRESHOLD * 100 -> 100
                orig == STOCK_THRESHOLD * 1000 -> 1000
                else -> 0
            }
            is Long -> when {
                orig == STOCK_THRESHOLD.toLong() -> 1
                orig == (STOCK_THRESHOLD * 10).toLong() -> 10
                orig == (STOCK_THRESHOLD * 100).toLong() -> 100
                orig == (STOCK_THRESHOLD * 1000).toLong() -> 1000
                else -> 0
            }
            else -> 0
        }
        if (scale == 0) return orig
        val scaled = (userPct * scale).toLong()
        return if (orig is Int) scaled.toInt() else scaled
    }

    /**
     * L2 核心：遍历类中所有方法，对「疑似阈值比较」的方法做 hook。
     *
     * 判定规则（任一命中）：
     *   a) 方法签名里参数类型是 int/long，方法名含 threshold/limit/min/level/battery
     *   b) 方法体里出现 25 常量比较（无法直接读字节码，改用返回值推断）
     */
    private fun hookAllComparisonMethods(cls: Class<*>, clsName: String) {
        val keywords = listOf("threshold", "limit", "minlevel", "minlevel", "lowbattery", "reverse")
        for (m in cls.declaredMethods) {
            val lname = m.name.lowercase()
            val matchedName = keywords.any { lname.contains(it) }
            if (!matchedName) continue
            if (m.parameterCount == 0 || m.parameterCount > 3) continue
            // 只 hook 返回 boolean / int 的（比较结果 / 阈值）
            val rt = m.returnType
            if (rt != Boolean::class.javaPrimitiveType &&
                rt != Int::class.javaPrimitiveType &&
                rt != Long::class.javaPrimitiveType &&
                rt != Void.TYPE
            ) continue

            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 若参数里有电量值（0~100 或 0~1000 标度），且当前低于 25 但 >= 用户阈值，
                        // 把参数「抬」到阈值以上，从而让原判断通过。
                        // v2.0 修复：userPct 原来写死 10，导致不管界面选多少都按 10% 处理。
                        val userPct = currentThreshold()
                        for (i in param.args.indices) {
                            val a = param.args[i] ?: continue
                            val n: Long = when (a) {
                                is Int -> a.toLong()
                                is Long -> a
                                else -> continue
                            }
                            // 电量标度推断：0~100 / 0~1000 / 0~10000
                            val pct = when {
                                n in 0..100 -> n
                                n in 0..1000 -> n / 10
                                n in 0..10000 -> n / 100
                                else -> continue
                            }
                            if (pct >= userPct && pct < STOCK_THRESHOLD) {
                                val boosted = when {
                                    n in 0..100 -> STOCK_THRESHOLD.toLong()
                                    n in 0..1000 -> (STOCK_THRESHOLD * 10).toLong()
                                    else -> (STOCK_THRESHOLD * 100).toLong()
                                }
                                param.args[i] = if (a is Int) boosted.toInt() else boosted
                                log("boosted arg[$i] in ${clsName}#${m.name}: $a -> $boosted")
                            }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 若方法返回的是阈值常量，改写
                        val r = param.result
                        if (r is Int || r is Long) {
                            param.result = patchThresholdValue(r)
                        }
                        // 若是 boolean 判断结果，且失败原因是电量——已在 before 抬升参数处理
                    }
                })
            } catch (_: Throwable) {
            }
        }
    }

    /** L1：hook 配置读取，让系统读到的阈值 = 用户设定值 */
    private fun hookConfigReaders(cl: ClassLoader) {
        val configClasses = listOf(
            "com.oplus.content.OplusFeatureConfigManager",
            "com.oplus.os.OplusBuild",
            "com.oplus.config.OplusConfig",
            "android.os.SystemProperties"
        )
        for (cn in configClasses) {
            try {
                val cls = XposedHelpers.findClass(cn, cl)

                // 常见：getInt(key, def) / getBoolean(key, def)
                cls.declaredMethods
                    .filter { it.name == "getInt" && it.parameterCount == 2 }
                    .forEach { m ->
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val key = param.args[0]?.toString()?.lowercase() ?: return
                                if (key.contains("reverse") && key.contains("level") ||
                                    key.contains("reverse") && key.contains("battery") ||
                                    key.contains("reversecharge") && key.contains("min")
                                ) {
                                    val orig = param.result
                                    if (orig == STOCK_THRESHOLD) {
                                        param.result = 10
                                        log("config $cn#$key : 25 -> 10")
                                    }
                                }
                            }
                        })
                    }

                // get(key) 返回 String 的也可能是配置
                cls.declaredMethods
                    .filter { it.name == "get" && it.parameterCount == 1 }
                    .forEach { m ->
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val key = param.args[0]?.toString()?.lowercase() ?: return
                                val r = param.result as? String ?: return
                                if (key.contains("reverse") && r.trim() == STOCK_THRESHOLD.toString()) {
                                    param.result = "10"
                                    log("config string $cn#$key : 25 -> 10")
                                }
                            }
                        })
                    }
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookBatteryService(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass("com.android.server.BatteryService", cl)
            // getPluggedMaxChargeVoltage / getBatteryLevelLow 等
            cls.declaredMethods.forEach { m ->
                val ln = m.name.lowercase()
                if ((ln.contains("reverse") || ln.contains("wireless")) &&
                    (ln.contains("enable") || ln.contains("allow") || ln.contains("support"))
                ) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val userPct = 10
                                for (i in param.args.indices) {
                                    val a = param.args[i]
                                    if (a is Int && a in userPct until STOCK_THRESHOLD) {
                                        param.args[i] = STOCK_THRESHOLD
                                        log("BatteryService boost arg in ${m.name}")
                                    }
                                }
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
            }
            log("BatteryService hooked")
        } catch (_: Throwable) {
        }
    }

    // =========================================================
    // 设置页：让灰掉的开关可点 + 显示正确文案
    // =========================================================
    private fun hookSettings(lp: XC_LoadPackage.LoadPackageParam) {
        log("Hooking settings: ${lp.processName}")
        genericHook(lp)

        // ColorOS 常见设置页 Fragment
        val fragClasses = listOf(
            "com.oplus.settings.wireless.WirelessReverseChargingPreferenceController",
            "com.oplus.settings.battery.ReverseChargingPreferenceController",
            "com.android.settings.wireless.WirelessReverseChargingController",
            "com.oplus.settings.OplusWirelessReverseChargingPreference"
        )
        for (cn in fragClasses) {
            try {
                val cls = XposedHelpers.findClass(cn, lp.classLoader)
                for (m in cls.declaredMethods) {
                    val ln = m.name.lowercase()
                    if (ln.contains("isenable") || ln.contains("isavailable") ||
                        ln.contains("canenable") || ln.contains("cansupport")
                    ) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true))
                        log("force-enable $cn#${m.name}")
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookOplusBattery(lp: XC_LoadPackage.LoadPackageParam) {
        log("Hooking oplus battery")
        // ★★★ v1.3 核心：ColorOS 真正读取反向充电电量门槛的唯一入口
        hookReverseThresholdReader(lp.classLoader)
        genericHook(lp)
    }

    /**
     * 【v1.3 核心】hook `f5.f.s1(Landroid/content/Context;)I`
     *
     * 反编译 com.oplus.battery 得到的原始实现（14 条指令）：
     * ```
     *   const/16 v0, #int 25
     *   const/4  v1, #int 0
     *   const-string v2, "wireless_reverse_battery_level_threshold"
     *   Settings$System.getIntForUser(resolver, v2, 25, 0)
     *   return v3
     * ```
     * 即：读 Settings.System 里该键，缺省 25。
     * 只要把返回值改掉，全系统所有「电量够不够开反向充电」的判断都会跟着变。
     *
     * 双保险：
     *   1. 直接替换 s1 的返回值 = 用户阈值（最稳，不依赖设置项是否可写）
     *   2. 同时 hook Settings$System.getIntForUser 里这个具体键，防止有别的
     *      类绕开 s1 直接读设置。
     */
    private fun hookReverseThresholdReader(cl: ClassLoader) {
        // ---- 兜底 A：Settings.System.getIntForUser 命中该键时改返回值 ----
        try {
            val sysCls = XposedHelpers.findClass("android.provider.Settings\$System", cl)
            sysCls.declaredMethods
                .filter { (it.name == "getIntForUser" || it.name == "getInt") && it.parameterCount >= 2 }
                .forEach { m ->
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val key = param.args[1]?.toString() ?: return
                                if (key != SYS_KEY) return
                                val r = param.result as? Int ?: return
                                if (r >= STOCK_THRESHOLD) {
                                    val ctx = contextOf(param.args[0])
                                    val user = readUserThreshold(ctx)
                                    param.result = user
                                    log("Settings.System#$key : $r -> $user")
                                }
                            }
                        })
                        log("Hooked Settings\$System#${m.name}(${m.parameterCount})")
                    } catch (_: Throwable) {
                    }
                }
        } catch (t: Throwable) {
            log("Settings\$System hook skipped: $t")
        }

        // ---- 兜底 B：直接替换 f5.f.s1(Context) 的返回值 ----
        try {
            val cls = XposedHelpers.findClass("f5.f", cl)
            var hit = false
            for (m in cls.declaredMethods) {
                if (m.name != "s1") continue
                if (m.parameterCount != 1) continue
                if (m.parameterTypes[0] != Context::class.java) continue
                if (m.returnType != Int::class.javaPrimitiveType) continue

                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val orig = param.result as? Int ?: return
                        val startCtx = (param.args[0] as? Context)?.let { cacheContext(it); it }
                        var user = readUserThreshold(startCtx)
                        // 若读不到 Context，退一步：只要原来是 25（默认值）就按 10 处理
                        if (startCtx == null) {
                            if (orig != STOCK_THRESHOLD) return
                            user = 10
                        }
                        if (user >= orig) return
                        param.result = user
                        log("f5.f.s1 : $orig -> $user")
                    }
                })
                hit = true
                log("Hooked f5.f.s1(Context)I  ★核心判定入口")
                break
            }
            if (!hit) log("f5.f.s1(Context)I not found (method list: ${cls.declaredMethods.filter { it.name == "s1" }.map { it.parameterTypes.joinToString { p -> p.simpleName } }})")
        } catch (t: Throwable) {
            log("f5.f hook skipped: $t")
        }
    }

    /** 从 hook 到的参数里尽量恢复出 Context（可能是 Context / ContentResolver） */
    private fun contextOf(arg: Any?): Context? {
        if (arg is Context) { cacheContext(arg); return arg }
        return appContext
    }

    /** 缓存一个 application context，供无法直接拿到 Context 的场景使用 */
    private fun cacheContext(ctx: Context?) {
        if (ctx == null) return
        if (appContext == null) {
            appContext = try { ctx.applicationContext ?: ctx } catch (_: Throwable) { ctx }
        }
    }

    private fun hookOplusAthena(lp: XC_LoadPackage.LoadPackageParam) {
        log("Hooking oplus athena")
        genericHook(lp)
    }

    private fun hookSafeCenter(lp: XC_LoadPackage.LoadPackageParam) {
        log("Hooking safecenter")
        genericHook(lp)
    }

    private fun hookOplusWireless(lp: XC_LoadPackage.LoadPackageParam) {
        log("Hooking oplus wirelesssettings")
        genericHook(lp)
    }

    /**
     * 通用 hook：扫描所有已加载类中，名字含无线充电/反向充电的类，
     * 对其中的「电量比较」方法做参数抬升。
     *
     * 注意：这里不在 ClassLoader.loadClass 上做全量扫描（太慢），
     * 而是列出常见的包前缀，逐个尝试。
     */
    private fun genericHook(lp: XC_LoadPackage.LoadPackageParam) {
        @Suppress("UNUSED_VARIABLE")
        val prefixes = listOf(
            "com.oplus.battery",
            "com.oplus.server.wireless",
            "com.oplus.settings.wireless",
            "com.oplus.wireless",
            "com.oplus.athena",
            "com.android.settings.wireless"
        )
        // 由于无法直接枚举已加载类，此处依赖前面的精确 hook。
        // 这里补一刀：hook Settings.Global.getInt 在本进程的调用。
        try {
            val sg = XposedHelpers.findClass("android.provider.Settings\$Global", lp.classLoader)
            sglHookOn(sg)
        } catch (_: Throwable) {
        }
    }

    private fun sglHookOn(cls: Class<*>) {
        cls.declaredMethods
            .filter { it.name == "getInt" && it.parameterCount == 3 }
            .forEach { m ->
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val key = param.args[1]?.toString()?.lowercase() ?: return
                            if (key.contains("reverse") && (key.contains("level") || key.contains("battery"))) {
                                if (param.result == STOCK_THRESHOLD) {
                                    param.result = 10
                                }
                            }
                        }
                    })
                } catch (_: Throwable) {
                }
            }
    }
}