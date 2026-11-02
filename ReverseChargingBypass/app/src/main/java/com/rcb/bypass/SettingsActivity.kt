package com.rcb.bypass

import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.Activity

/**
 * 模块设置界面。
 *
 * 因为被 hook 的是 system_server / 设置等**其他进程**，
 * 无法用 SharedPreferences 简单跨进程读取，所以这里把用户选择
 * 写入 Settings.Global（所有进程都能读），MainHook 也从那里读。
 *
 * 写入 Settings.Global 需要 WRITE_SECURE_SETTINGS 权限，
 * 普通 App 拿不到。因此提供两条路径：
 *   1) 有 root/shizuku 时，用 `settings put global ...` 命令写入（下方按钮提示命令）
 *   2) 模块自身在 SystemUI 等 hook 进程中代为写入（首次打开界面时尝试）
 *
 * 简化处理：本界面只做选择 + 展示需要执行的 adb 命令，
 * 同时尝试直接写入（若已授予 WRITE_SECURE_SETTINGS 则成功）。
 */
class SettingsActivity : Activity() {

    private val options = listOf(5, 10, 15, 20)
    private var selected = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 恢复上次选择
        selected = try {
            Settings.Global.getInt(contentResolver, "rcb_reverse_threshold", 10)
        } catch (_: Throwable) {
            10
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "反向无线充电阈值解锁"
            textSize = 20f
            setPadding(0, 0, 0, 8)
        }
        root.addView(title)

        val desc = TextView(this).apply {
            text = "ColorOS 默认要求电量 ≥ 25% 才能开启无线反向充电。\n" +
                    "本模块把该门槛替换为你选择的档位。"
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }
        root.addView(desc)

        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        options.forEach { pct ->
            val rb = RadioButton(this).apply {
                id = pct
                text = "${pct}%"
                textSize = 16f
                isChecked = (pct == selected)
                setPadding(0, 16, 0, 16)
            }
            group.addView(rb, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        group.setOnCheckedChangeListener { _, checkedId -> selected = checkedId }
        root.addView(group)

        val applyBtn = Button(this).apply {
            text = "应用设置"
            setOnClickListener { apply() }
        }
        root.addView(applyBtn)

        val cmdLabel = TextView(this).apply {
            text = "\n若「应用设置」提示无权限，请在电脑/终端执行一次：\n" +
                    "adb shell settings put global rcb_reverse_threshold $selected\n" +
                    "（或 root 下直接执行同命令）\n\n" +
                    "修改后需重启手机或重启相关进程生效。"
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }
        root.addView(cmdLabel)

        val stateLabel = TextView(this).apply {
            text = "\n当前系统内记录值：" + currentValue()
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }
        root.addView(stateLabel)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun currentValue(): String = try {
        Settings.Global.getInt(contentResolver, "rcb_reverse_threshold", -1)
            .takeIf { it > 0 }?.toString() ?: "未设置（默认 10）"
    } catch (e: Throwable) {
        "读取失败"
    }

    private fun apply() {
        var ok = false
        try {
            Settings.Global.putInt(contentResolver, "rcb_reverse_threshold", selected)
            ok = true
        } catch (_: Throwable) {
        }
        // 同时写系统真实键（ColorOS 判定用的就是它），需要 WRITE_SETTINGS / root
        try {
            Settings.System.putInt(contentResolver, "wireless_reverse_battery_level_threshold", selected)
            ok = true
        } catch (_: Throwable) {
        }

        if (ok) {
            Toast.makeText(this, "已设为 $selected%（重启相关进程后生效）", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                this,
                "无写设置权限，请用 root/adb 执行：\n" +
                        "settings put global rcb_reverse_threshold $selected\n" +
                        "settings put system wireless_reverse_battery_level_threshold $selected",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}