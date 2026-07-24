package com.haval.h6.steeringmapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 方向盘按键拦截核心服务
 *
 * 工作原理：
 * 1. 注册为 AccessibilityService，获得全局 KeyEvent 拦截权限
 * 2. 识别 *键（keyCode=189，adayo 车机上报值）作为"功能修饰键"
 * 3. 在 *键按下后的 1.5s 内，拦截下一个按键作为"功能键"
 * 4. 查询 KeyMappingManager 获取对应 HvacAction，交由 HvacController 执行
 *
 * 注意：onKeyEvent 返回 true 表示拦截该按键（不传递给系统），false 表示透传
 */
class SteeringKeyService : AccessibilityService() {

    companion object {
        private const val TAG = "SteeringKeyService"
        const val KEY_COMBO_TIMEOUT_MS = 1500L  // 组合键超时：1.5秒内按第二个键才算组合

        // 广播 Action，供 MainActivity / KeyDetectActivity 通信
        const val ACTION_SERVICE_STARTED  = "com.haval.h6.steeringmapper.SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED  = "com.haval.h6.steeringmapper.SERVICE_STOPPED"
        const val ACTION_KEY_DETECTED     = "com.haval.h6.steeringmapper.KEY_DETECTED"
        const val ACTION_SET_DETECT_MODE  = "com.haval.h6.steeringmapper.SET_DETECT_MODE"

        const val EXTRA_KEYCODE     = "keycode"
        const val EXTRA_KEY_NAME    = "keyname"
        const val EXTRA_ACTION_NAME = "action_name"

        /** 服务是否运行（给 MainActivity 判断用） */
        @Volatile
        var isRunning = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var hvacController: HvacController? = null
    private var keyMappingManager: KeyMappingManager? = null

    // ===== 组合键状态机 =====
    private var isStarKeyDown  = false
    private var waitingForCombo = false

    private val comboTimeoutRunnable = Runnable {
        if (waitingForCombo) {
            waitingForCombo = false
            isStarKeyDown = false
            Log.d(TAG, "组合键超时，取消等待")
        }
    }

    // ===== 按键检测模式（供 KeyDetectActivity 使用）=====
    @Volatile var isKeyDetectMode = false

    /**
     * 接收 KeyDetectActivity 发来的检测模式切换广播
     * 广播 Action：ACTION_SET_DETECT_MODE，Extra "enabled": Boolean
     */
    private val detectModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SET_DETECT_MODE) {
                isKeyDetectMode = intent.getBooleanExtra("enabled", false)
                Log.i(TAG, "按键检测模式切换为: $isKeyDetectMode")
            }
        }
    }

    // ====================================================

    override fun onServiceConnected() {
        Log.i(TAG, "AccessibilityService 已连接")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // FLAG_REQUEST_FILTER_KEY_EVENTS 是收到 onKeyEvent 的关键
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }

        hvacController    = HvacController(this)
        keyMappingManager = KeyMappingManager(this)
        isRunning = true

        // 注册检测模式切换广播
        registerReceiver(
            detectModeReceiver,
            IntentFilter(ACTION_SET_DETECT_MODE)
        )

        sendBroadcast(Intent(ACTION_SERVICE_STARTED))
        Log.i(TAG, "服务初始化完成，按键监听已激活")
    }

    /**
     * 核心方法：拦截所有 KeyEvent
     * 返回 true = 消费此事件（不传给系统）
     * 返回 false = 透传此事件
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action  = event.action

        // ===== 按键检测模式：只上报，不消费 =====
        if (isKeyDetectMode) {
            if (action == KeyEvent.ACTION_DOWN) reportKeyDetected(keyCode)
            return false
        }

        // ===== 正常模式：组合键状态机 =====
        return handleKeyCombo(keyCode, action)
    }

    /**
     * 组合键状态机
     * - 按下 * → 进入等待组合键状态
     * - 等待中按下另一个键 → 查映射表执行动作，消费该键
     * - 超时 / 松开 * 且没按其他键 → 透传 * 给系统
     */
    private fun handleKeyCombo(keyCode: Int, action: Int): Boolean {
        val starKeyCode = keyMappingManager?.getStarKeyCode() ?: 189  // 哈弗H6三代 * 键 keyCode

        return when {
            // * 键按下：开始等待组合
            keyCode == starKeyCode && action == KeyEvent.ACTION_DOWN -> {
                if (!isStarKeyDown) {
                    isStarKeyDown   = true
                    waitingForCombo = true
                    handler.removeCallbacks(comboTimeoutRunnable)
                    handler.postDelayed(comboTimeoutRunnable, KEY_COMBO_TIMEOUT_MS)
                    Log.d(TAG, "* 键按下，等待组合键...")
                }
                true  // 消费 * 键 DOWN
            }

            // * 键松开：判断是否需要补发
            keyCode == starKeyCode && action == KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(comboTimeoutRunnable)
                val wasWaiting = waitingForCombo
                isStarKeyDown   = false
                waitingForCombo = false
                if (wasWaiting) {
                    Log.d(TAG, "* 键单独释放，透传给系统")
                    injectKeyEvent(starKeyCode)
                }
                true  // UP 事件本身消费掉
            }

            // 等待组合中，收到第二个键（DOWN）
            waitingForCombo && action == KeyEvent.ACTION_DOWN -> {
                handler.removeCallbacks(comboTimeoutRunnable)
                waitingForCombo = false
                isStarKeyDown   = false

                Log.d(TAG, "检测到组合键：* + ${KeyEvent.keyCodeToString(keyCode)}")
                val mapping = keyMappingManager?.findMapping(starKeyCode, keyCode)
                if (mapping != null) {
                    Log.i(TAG, "执行动作：${mapping.action}")
                    executeAction(mapping.action, keyCode)
                    true   // 消费第二个键
                } else {
                    Log.d(TAG, "未找到映射，透传 * 和当前键")
                    injectKeyEvent(starKeyCode)
                    false  // 不消费第二个键，透传给系统
                }
            }

            else -> false  // 其他按键完全透传
        }
    }

    /** 执行空调动作，并向 UI 广播结果 */
    private fun executeAction(action: HvacAction, triggerKeyCode: Int) {
        val ctrl = hvacController ?: return
        when (action) {
            HvacAction.TEMP_UP              -> ctrl.adjustTemperature(+0.5f)
            HvacAction.TEMP_DOWN            -> ctrl.adjustTemperature(-0.5f)
            HvacAction.FAN_SPEED_UP         -> ctrl.adjustFanSpeed(+1)
            HvacAction.FAN_SPEED_DOWN       -> ctrl.adjustFanSpeed(-1)
            HvacAction.RECIRCULATION_TOGGLE -> ctrl.toggleRecirculation()
            HvacAction.AC_TOGGLE            -> ctrl.toggleAC()
            HvacAction.AUTO_MODE            -> ctrl.setAutoMode()
            HvacAction.FRONT_DEFROST        -> ctrl.toggleFrontDefrost()
            HvacAction.REAR_DEFROST         -> ctrl.toggleRearDefrost()
            HvacAction.HVAC_POWER_TOGGLE    -> ctrl.toggleHvacPower()
            HvacAction.BLOWER_DIR_FACE,
            HvacAction.BLOWER_DIR_FEET,
            HvacAction.BLOWER_DIR_BOTH      -> ctrl.cycleBlowerDirection()  // 待实车确认后可再细分
        }
        sendBroadcast(Intent(ACTION_KEY_DETECTED).apply {
            putExtra(EXTRA_KEYCODE, triggerKeyCode)
            putExtra(EXTRA_ACTION_NAME, action.displayName)
        })
    }

    /** 上报按键检测结果（供 KeyDetectActivity 显示） */
    private fun reportKeyDetected(keyCode: Int) {
        val keyName = KeyEvent.keyCodeToString(keyCode)
        sendBroadcast(Intent(ACTION_KEY_DETECTED).apply {
            putExtra(EXTRA_KEYCODE, keyCode)
            putExtra(EXTRA_KEY_NAME, keyName)
        })
        Log.d(TAG, "检测到按键: $keyName ($keyCode)")
    }

    /**
     * 模拟注入一个按键事件（用于补发被消费的 * 键）
     * App 层无法直接注入 InputEvent，此处仅记录日志。
     * 若需要真正注入，可通过 ADB shell input keyevent 或系统签名方式实现。
     */
    private fun injectKeyEvent(keyCode: Int) {
        Log.d(TAG, "注: * 键(keyCode=$keyCode)已被消费，如需透传请使用 shell 方案")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString() ?: return
            hvacController?.onForegroundPackageChanged(pkgName)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService 被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(detectModeReceiver) } catch (_: Exception) {}
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        Log.i(TAG, "服务已销毁")
    }
}
