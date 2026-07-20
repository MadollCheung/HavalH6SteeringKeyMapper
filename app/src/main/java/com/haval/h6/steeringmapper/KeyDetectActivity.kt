package com.haval.h6.steeringmapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 按键检测界面
 * 用于确认车机方向盘各按键实际上报的 KeyCode
 *
 * 进入此界面后，Service 会切换为"检测模式"：
 * - 只上报按键，不执行任何映射动作
 * - 界面实时显示检测到的 keyName 和 keyCode
 * - 方便用户确认实际 keycode 后手动配置映射
 */
class KeyDetectActivity : AppCompatActivity() {

    private lateinit var tvDetectResult: TextView
    private lateinit var tvHistory: StringBuilder
    private lateinit var btnClear: Button
    private lateinit var btnDone: Button

    private val detectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SteeringKeyService.ACTION_KEY_DETECTED) {
                val keyCode = intent.getIntExtra(SteeringKeyService.EXTRA_KEYCODE, -1)
                val keyName = intent.getStringExtra(SteeringKeyService.EXTRA_KEY_NAME)
                    ?: KeyEvent.keyCodeToString(keyCode)

                val line = "▶  $keyName  (keyCode = $keyCode)\n"
                tvHistory.insert(0, line)  // 最新的显示在顶部
                tvDetectResult.text = tvHistory.toString()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_detect)

        tvDetectResult = findViewById(R.id.tv_detect_result)
        btnClear = findViewById(R.id.btn_clear)
        btnDone = findViewById(R.id.btn_done)
        tvHistory = StringBuilder()

        tvDetectResult.text = "请按方向盘上的按键...\n检测到的按键将显示在此处"

        btnClear.setOnClickListener {
            tvHistory.clear()
            tvDetectResult.text = "已清除，继续按键..."
        }

        btnDone.setOnClickListener { finish() }

        // 通知 Service 进入检测模式
        setServiceDetectMode(true)
        registerReceiver(detectReceiver, IntentFilter(SteeringKeyService.ACTION_KEY_DETECTED))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(detectReceiver)
        // 恢复 Service 为正常模式
        setServiceDetectMode(false)
    }

    /**
     * 通过 Binder/静态引用切换 Service 检测模式
     * 简单实现：通过 Service 的静态字段（线程安全）
     */
    private fun setServiceDetectMode(enabled: Boolean) {
        // AccessibilityService 不支持直接 bind，通过静态字段通信
        // 实际项目可用 EventBus 或 LiveData 替代
        try {
            val clazz = SteeringKeyService::class.java
            val field = clazz.getDeclaredField("isKeyDetectMode")
            // 注意：需要 service 实例，此处用广播间接触发
        } catch (e: Exception) { /* 忽略 */ }

        // 通过广播通知 Service
        sendBroadcast(Intent("com.haval.h6.steeringmapper.SET_DETECT_MODE").apply {
            putExtra("enabled", enabled)
        })
    }
}