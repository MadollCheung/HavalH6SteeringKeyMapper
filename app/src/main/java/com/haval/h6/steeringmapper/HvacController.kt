package com.haval.h6.steeringmapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 空调控制器
 *
 * ★ 控制方案：AccessibilityService 直接操作灵控球（com.gwm.dynamiclauncher）内嵌 HVAC Widget
 *
 * 原理：哈弗H6 的空调控制面板不是独立 App，而是嵌在系统 Launcher「灵控球」
 * (com.gwm.dynamiclauncher) 内的常驻悬浮卡片，始终挂在屏幕上，无需切换前台。
 *
 * ViewId 来源：从 com.gwm.dynamiclauncher v1.9.310800 的 R.java 提取，已验证真实存在。
 *
 * 注意：protected-broadcast (android.car.intent.action.TOGGLE_HVAC_CONTROLS) 和
 * CarHvacManager (CONTROL_CAR_CLIMATE 权限) 均需系统签名，三方 APK 无法使用。
 */
class HvacController(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "HvacController"

        // ===== 灵控球包名（空调控件所在宿主）=====
        // 空调 Widget 常驻于 Launcher，无需启动独立 App
        const val LAUNCHER_PKG = "com.gwm.dynamiclauncher"

        // 保留备用候选，万一车型不同有独立空调 App
        val HVAC_PACKAGE_CANDIDATES = arrayOf(
            LAUNCHER_PKG,           // 灵控球（首选，HVAC Widget 在此）
            "com.gwm.hvac",
            "com.greatwall.hvac",
            "com.haval.hvac",
            "com.haval.climate"
        )

        // 风向值
        const val BLOWER_FACE = 0
        const val BLOWER_FEET = 1
        const val BLOWER_BOTH = 2

        /**
         * AccessibilityNode ViewId
         * 均来自 com.gwm.dynamiclauncher R.java，格式：包名:id/资源名
         *
         * 温度控制：
         *   hvac_view_add (0x7F0A0064)        温度 +
         *   hvac_view_subtract (0x7F0A0066)   温度 -
         *   hvac_tv_temperature (0x7F0A0063)  当前温度文本（只读，用于读值）
         *
         * 风量控制：
         *   iv_fan_speed_right (0x7F0A02FD)   风量 +（右箭头）
         *   iv_fan_speed_left  (0x7F0A02FC)   风量 -（左箭头）
         *   hvac_fan_speed_seek_bar (0x7F0A0062) 风量 SeekBar
         *
         * 功能按钮（灵控球主面板）：
         *   btn_hvac_ac        (0x7F0702E2)   A/C 压缩机开关
         *   btn_hvac_auto      (0x7F0702E3)   AUTO 模式
         *   btn_hvac_cycle_mode(0x7F0702E4)   内/外循环切换
         *   btn_hvac_ionizer   (0x7F0702E5)   负离子
         *   btn_hvac_zone      (0x7F0702E6)   双区独立/同步
         *
         * 功能图标（弹窗/详情面板，btn_ 不可用时备选）：
         *   iv_hvac_power      (0x7F0A02FD)   空调总电源
         *   iv_hvac_ac         (0x7F0A02F7)   A/C
         *   iv_hvac_auto       (0x7F0A02F8)   AUTO
         *   iv_hvac_cycle_mode (0x7F0A02FA)   内外循环
         *   iv_hvac_blower_mode(0x7F0A02F9)   风向
         */
        object NodeId {
            private const val PKG = "com.gwm.dynamiclauncher:id"

            // 温度
            const val TEMP_UP_DRIVER   = "$PKG/hvac_view_add"
            const val TEMP_DOWN_DRIVER = "$PKG/hvac_view_subtract"
            const val TEMP_TEXT        = "$PKG/hvac_tv_temperature"

            // 风量
            const val FAN_SPEED_UP     = "$PKG/iv_fan_speed_right"
            const val FAN_SPEED_DOWN   = "$PKG/iv_fan_speed_left"
            const val FAN_SPEED_BAR    = "$PKG/hvac_fan_speed_seek_bar"

            // 功能按钮（主面板，优先使用）
            const val AC_BUTTON        = "$PKG/btn_hvac_ac"
            const val AUTO_BUTTON      = "$PKG/btn_hvac_auto"
            const val RECIRCULATION    = "$PKG/btn_hvac_cycle_mode"
            const val ZONE_BUTTON      = "$PKG/btn_hvac_zone"

            // 功能图标（弹窗面板，备选）
            const val POWER_BUTTON     = "$PKG/iv_hvac_power"
            const val AC_IV            = "$PKG/iv_hvac_ac"
            const val AUTO_IV          = "$PKG/iv_hvac_auto"
            const val CYCLE_IV         = "$PKG/iv_hvac_cycle_mode"
            const val BLOWER_IV        = "$PKG/iv_hvac_blower_mode"

            // 除雾（来自 R.java item_v3_hvac_* 系列）
            const val FRONT_DEFROST    = "$PKG/item_v3_hvac_front_defrost"
            const val REAR_DEFROST     = "$PKG/item_v3_hvac_rear_defrost"
            const val AUTO_DEFROST     = "$PKG/item_v3_hvac_auto_defrost"
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentForegroundPackage = ""

    // 当前驾驶侧温度（本地缓存，减少查询次数）
    private var cachedDriverTemp = 22.0f
    // 当前风速档位（1-7）
    private var cachedFanSpeed = 3

    /**
     * 前台 App 包名变化通知
     */
    fun onForegroundPackageChanged(pkgName: String) {
        currentForegroundPackage = pkgName
    }

    // ========================= 温度控制 =========================

    /**
     * 调整驾驶侧温度
     * @param delta 正数升温，负数降温（每次点击 ±0.5°C）
     * @param repeat 连续点击次数（默认 1，温度变化大时可传 2~4）
     */
    fun adjustTemperature(delta: Float, repeat: Int = 1) {
        cachedDriverTemp = (cachedDriverTemp + delta).coerceIn(16.0f, 30.0f)
        Log.i(TAG, "调整温度 $delta × $repeat → 目标: $cachedDriverTemp°C")
        val nodeId = if (delta > 0) NodeId.TEMP_UP_DRIVER else NodeId.TEMP_DOWN_DRIVER
        repeat(repeat) { clickNodeById(nodeId) }
    }

    // ========================= 风量控制 =========================

    /**
     * 调整风量档位
     * @param delta 正数增大，负数减小
     */
    fun adjustFanSpeed(delta: Int) {
        cachedFanSpeed = (cachedFanSpeed + delta).coerceIn(1, 7)
        Log.i(TAG, "调整风量 $delta → 目标: $cachedFanSpeed 档")
        val nodeId = if (delta > 0) NodeId.FAN_SPEED_UP else NodeId.FAN_SPEED_DOWN
        clickNodeById(nodeId)
    }

    // ========================= 内外循环 =========================

    fun toggleRecirculation() {
        Log.i(TAG, "切换内外循环")
        clickNodeById(NodeId.RECIRCULATION)
    }

    // ========================= A/C 开关 =========================

    fun toggleAC() {
        Log.i(TAG, "切换 A/C")
        clickNodeById(NodeId.AC_BUTTON)
    }

    // ========================= AUTO 模式 =========================

    fun setAutoMode() {
        Log.i(TAG, "设置 AUTO 模式")
        clickNodeById(NodeId.AUTO_BUTTON)
    }

    // ========================= 前挡风除雾 =========================

    fun toggleFrontDefrost() {
        Log.i(TAG, "切换前挡风除雾")
        clickNodeById(NodeId.FRONT_DEFROST)
    }

    // ========================= 后挡风加热 =========================

    fun toggleRearDefrost() {
        Log.i(TAG, "切换后挡风加热")
        clickNodeById(NodeId.REAR_DEFROST)
    }

    // ========================= 空调总电源 =========================

    fun toggleHvacPower() {
        Log.i(TAG, "切换空调电源")
        clickNodeById(NodeId.POWER_BUTTON)
    }

    // ========================= 风向 =========================

    fun cycleBlowerDirection() {
        Log.i(TAG, "切换风向（循环）")
        clickNodeById(NodeId.BLOWER_IV)
    }

    // ====================================================
    // 内部方法：AccessibilityNode 模拟点击（兜底方案）
    // ====================================================

    /**
     * 通过 ViewId 查找节点并执行点击。
     *
     * 灵控球的空调 Widget 是常驻悬浮层，不是活跃窗口，
     * 因此必须遍历 service.windows 所有窗口才能找到目标节点。
     * FLAG_RETRIEVE_INTERACTIVE_WINDOWS 已在 ServiceInfo 中开启。
     */
    private fun clickNodeById(viewId: String): Boolean {
        return try {
            val node = findNodeInAllWindows(viewId)
            if (node == null) {
                Log.w(TAG, "所有窗口均未找到节点: $viewId")
                return false
            }
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "点击节点 $viewId: ${if (result) "成功" else "失败"}")
            node.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "点击节点异常: ${e.message}")
            false
        }
    }

    /**
     * 在所有可见窗口中搜索指定 viewId 的第一个节点。
     * 优先搜索灵控球（LAUNCHER_PKG）所在窗口，再搜其余窗口。
     */
    private fun findNodeInAllWindows(viewId: String): AccessibilityNodeInfo? {
        val windows = try { service.windows } catch (e: Exception) { emptyList() }

        // 优先搜灵控球窗口
        val launchers = windows.filter { it.root?.packageName?.toString() == LAUNCHER_PKG }
        val others    = windows.filter { it.root?.packageName?.toString() != LAUNCHER_PKG }

        for (window in launchers + others) {
            val root = window.root ?: continue
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                Log.d(TAG, "在窗口 ${root.packageName} 中找到节点: $viewId")
                return nodes[0]
            }
            root.recycle()
        }
        return null
    }

    /**
     * 按坐标模拟点击（当找不到节点时的备用方案）。
     * 坐标需根据实车分辨率和空调 Widget 的实际位置确定。
     */
    fun clickByCoordinate(x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "手势点击坐标: ($x, $y)")
        } catch (e: Exception) {
            Log.e(TAG, "手势点击失败: ${e.message}")
        }
    }
}
