package com.rcb.bypass

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * v1.5：顶部状态栏快捷磁贴。
 *
 * 交互设计（v1.5.1 起改为"优雅式"）：
 *  - 点一下：只轻量地弹出 [RcbPanelActivity] 小窗，不跳整页、不立刻重启进程；
 *  - 长按磁贴：系统会命中 SETTINGS_ACTIVITY（Manifest 里已指向同一个 panel），
 *    效果等同于点开面板，所以长按和点按的行为是一致的、可预期的。
 *
 * 磁贴本身只做状态展示（当前阈值），不再承担"一键重启"这种重动作，
 * 避免误触一次就把后台进程全部杀掉。
 */
class RcbTileService : TileService() {

    companion object {
        const val GLOBAL_KEY = "rcb_reverse_threshold"
        const val DEFAULT_PCT = 10

        fun readThreshold(ctx: android.content.Context): Int = try {
            Settings.Global.getInt(ctx.contentResolver, GLOBAL_KEY, DEFAULT_PCT)
        } catch (_: Throwable) {
            DEFAULT_PCT
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    /** 点按磁贴：收起状态栏，浮出小窗。 */
    override fun onClick() {
        super.onClick()
        openPanel()
    }

    private fun openPanel() {
        try {
            val intent = Intent(this, RcbPanelActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            // startActivityAndCollapse 会自动收起快捷面板，
            // 这样小窗浮出时不会和状态栏叠在一起。
            startActivityAndCollapse(intent)
        } catch (_: Throwable) {
            try {
                val fallback = Intent(this, RcbPanelActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
                main.postDelayed({ refresh() }, 800)
            } catch (_: Throwable) {
            }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val pct = readThreshold(this)
        tile.state = Tile.STATE_ACTIVE
        tile.label = "反向充电"
        tile.subtitle = "阈值 ${pct}%"
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_idle_charging)
        tile.updateTile()
    }
}