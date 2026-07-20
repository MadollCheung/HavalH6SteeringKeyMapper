package com.haval.h6.steeringmapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 空调控制器
 *
 * 控制方案（双路）：
 * 1. Broadcast Intent 方案（优先）：发送长城车机内部广播
 * 2. AccessibilityService 模拟触控（兜底）：打开空调界面，查找控件节点，执行 click
 *
 * 哈弗H6三代（华阳100A/安波福403A）空调 App 包名：
 * - 主要包名：com.greatwall.hvac 或 com.haval.hvac
 * - Activity：com.greatwall.hvac.ui.HvacActivity
 * 具体包名以实际车机为准（用 ADB "dumpsys window windows | grep mCurrentFocus" 查看）
 */
class HvacController(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "HvacController"

        // ===== 哈弗H6三代空调 App 包名（可能因版本而异）=====
        // 建议用 adb shell dumpsys window windows | findstr "mCurrentFocus" 确认
        // 注意：arrayOf 不能用 const val，改为普通 val
        val HVAC_PACKAGE_CANDIDATES = arrayOf(
            "com.greatwall.hvac",
            "com.haval.hvac",
            "com.gwm.hvac",
            "com.haval.climate",
            "com.greatwall.climate"
        )

        // ===== 长城车机内部广播 Action（如果支持）=====
        const val BROADCAST_HVAC_CONTROL = "com.greatwall.hvac.action.CONTROL"
        const val BROADCAST_HVAC_CONTROL_ALT = "com.gwm.hvac.CONTROL"

        // 广播参数 Key
        const val EXTRA_COMMAND = "command"
        const val EXTRA_VALUE = "value"
        const val EXTRA_ZONE = "zone"   // 0=驾驶侧, 1=副驾侧

        // 广播 command 值
        const val CMD_TEMP_SET = "set_temperature"
        const val CMD_FAN_SPEED = "set_fan_speed"
        const val CMD_RECIRCULATION = "set_recirculation"
        const val CMD_AC = "set_ac"
        const val CMD_AUTO = "set_auto"
        const val CMD_FRONT_DEFROST = "set_front_defrost"
        const val CMD_REAR_DEFROST = "set_rear_defrost"
        const val CMD_POWER = "set_power"
        const val CMD_BLOWER_DIR = "set_blower_direction"

        // 风向值
        const val BLOWER_FACE = 0
        const val BLOWER_FEET = 1
        const val BLOWER_BOTH = 2

        // AccessibilityNode ViewId（根据实际 APK 布局确定，此处为估计值）
        // 建议用 uiautomatorviewer 或 ADB 命令查看实际 viewId
        object NodeId {
            const val TEMP_UP_DRIVER = "com.greatwall.hvac:id/btn_temp_up_driver"
            const val TEMP_DOWN_DRIVER = "com.greatwall.hvac:id/btn_temp_down_driver"
            const val FAN_SPEED_UP = "com.greatwall.hvac:id/btn_fan_up"
            const val FAN_SPEED_DOWN = "com.greatwall.hvac:id/btn_fan_down"
            const val RECIRCULATION = "com.greatwall.hvac:id/btn_recirculation"
            const val AC_BUTTON = "com.greatwall.hvac:id/btn_ac"
            const val AUTO_BUTTON = "com.greatwall.hvac:id/btn_auto"
            const val FRONT_DEFROST = "com.greatwall.hvac:id/btn_front_defrost"
            const val REAR_DEFROST = "com.greatwall.hvac:id/btn_rear_defrost"
            const val POWER_BUTTON = "com.greatwall.hvac:id/btn_power"
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

    fun adjustTemperature(delta: Float) {
        cachedDriverTemp = (cachedDriverTemp + delta).coerceIn(16.0f, 30.0f)
        Log.i(TAG, "调整温度 $delta → 目标: $cachedDriverTemp°C")

        val success = sendHvacBroadcast(CMD_TEMP_SET, cachedDriverTemp.toString())
        if (!success) {
            // 广播失败，改用模拟点击
            val nodeId = if (delta > 0) NodeId.TEMP_UP_DRIVER else NodeId.TEMP_DOWN_DRIVER
            clickNodeById(nodeId)
        }
    }

    // ========================= 风量控制 =========================

    fun adjustFanSpeed(delta: Int) {
        cachedFanSpeed = (cachedFanSpeed + delta).coerceIn(1, 7)
        Log.i(TAG, "调整风量 $delta → 目标: $cachedFanSpeed 档")

        val success = sendHvacBroadcast(CMD_FAN_SPEED, cachedFanSpeed.toString())
        if (!success) {
            val nodeId = if (delta > 0) NodeId.FAN_SPEED_UP else NodeId.FAN_SPEED_DOWN
            clickNodeById(nodeId)
        }
    }

    // ========================= 内外循环 =========================

    fun toggleRecirculation() {
        Log.i(TAG, "切换内外循环")
        val success = sendHvacBroadcast(CMD_RECIRCULATION, "toggle")
        if (!success) clickNodeById(NodeId.RECIRCULATION)
    }

    // ========================= A/C 开关 =========================

    fun toggleAC() {
        Log.i(TAG, "切换 A/C")
        val success = sendHvacBroadcast(CMD_AC, "toggle")
        if (!success) clickNodeById(NodeId.AC_BUTTON)
    }

    // ========================= AUTO 模式 =========================

    fun setAutoMode() {
        Log.i(TAG, "设置 AUTO 模式")
        val success = sendHvacBroadcast(CMD_AUTO, "on")
        if (!success) clickNodeById(NodeId.AUTO_BUTTON)
    }

    // ========================= 前挡风除雾 =========================

    fun toggleFrontDefrost() {
        Log.i(TAG, "切换前挡风除雾")
        val success = sendHvacBroadcast(CMD_FRONT_DEFROST, "toggle")
        if (!success) clickNodeById(NodeId.FRONT_DEFROST)
    }

    // ========================= 后挡风加热 =========================

    fun toggleRearDefrost() {
        Log.i(TAG, "切换后挡风加热")
        val success = sendHvacBroadcast(CMD_REAR_DEFROST, "toggle")
        if (!success) clickNodeById(NodeId.REAR_DEFROST)
    }

    // ========================= 空调总电源 =========================

    fun toggleHvacPower() {
        Log.i(TAG, "切换空调电源")
        val success = sendHvacBroadcast(CMD_POWER, "toggle")
        if (!success) clickNodeById(NodeId.POWER_BUTTON)
    }

    // ========================= 风向 =========================

    fun setBlowerDirection(direction: Int) {
        val dirStr = when (direction) {
            BLOWER_FACE -> "face"
            BLOWER_FEET -> "feet"
            BLOWER_BOTH -> "both"
            else -> "face"
        }
        Log.i(TAG, "设置风向: $dirStr")
        sendHvacBroadcast(CMD_BLOWER_DIR, dirStr)
        // 风向按键的 nodeId 需根据实际布局扫描，此处略
    }

    // ====================================================
    // 内部方法：发送广播
    // ====================================================

    /**
     * 向车机系统发送空调控制广播
     * @return true=广播已发送（不代表成功执行），false=发送失败
     *
     * 注意：如果车机系统没有注册对应的 BroadcastReceiver，广播会被忽略
     * 可以通过 logcat 过滤 "com.greatwall.hvac" 确认是否收到
     */
    private fun sendHvacBroadcast(command: String, value: String, zone: Int = 0): Boolean {
        return try {
            val intent = Intent(BROADCAST_HVAC_CONTROL).apply {
                putExtra(EXTRA_COMMAND, command)
                putExtra(EXTRA_VALUE, value)
                putExtra(EXTRA_ZONE, zone)
                // 指定包名，避免广播被其他 App 拦截
                setPackage(HVAC_PACKAGE_CANDIDATES[0])
            }
            service.sendBroadcast(intent)

            // 同时尝试备用 Action
            val intent2 = Intent(BROADCAST_HVAC_CONTROL_ALT).apply {
                putExtra(EXTRA_COMMAND, command)
                putExtra(EXTRA_VALUE, value)
                putExtra(EXTRA_ZONE, zone)
            }
            service.sendBroadcast(intent2)

            Log.d(TAG, "广播已发送: command=$command, value=$value")
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送广播失败: ${e.message}")
            false
        }
    }

    // ====================================================
    // 内部方法：AccessibilityNode 模拟点击（兜底方案）
    // ====================================================

    /**
     * 通过 ViewId 查找节点并执行点击
     * 注意：需要空调 App 在前台（或至少在后台可见）
     */
    private fun clickNodeById(viewId: String): Boolean {
        return try {
            // 优先在当前窗口查找
            val rootNode = service.rootInActiveWindow ?: run {
                Log.w(TAG, "无法获取根节点，尝试打开空调界面")
                openHvacApp()
                return false
            }

            val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes.isNullOrEmpty()) {
                Log.w(TAG, "节点未找到: $viewId，尝试打开空调界面后重试")
                openHvacApp()
                // 延迟后重试
                handler.postDelayed({
                    retryClickNodeById(viewId)
                }, 800)
                return false
            }

            val node = nodes[0]
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
     * 延迟后重试点击（用于打开空调 App 后）
     */
    private fun retryClickNodeById(viewId: String) {
        try {
            val rootNode = service.rootInActiveWindow ?: return
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                nodes[0].recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "重试点击失败: ${e.message}")
        }
    }

    /**
     * 按坐标模拟点击（当找不到节点时的最后手段）
     * 坐标需根据你的车机分辨率和空调界面布局确定
     */
    private fun clickByCoordinate(x: Float, y: Float) {
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

    /**
     * 打开空调 App
     */
    private fun openHvacApp() {
        for (pkgName in HVAC_PACKAGE_CANDIDATES) {
            try {
                val intent = service.packageManager.getLaunchIntentForPackage(pkgName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    service.startActivity(intent)
                    Log.i(TAG, "已启动空调 App: $pkgName")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动 $pkgName 失败: ${e.message}")
            }
        }
        Log.e(TAG, "未找到空调 App，请在设置中配置正确的包名")
    }
}