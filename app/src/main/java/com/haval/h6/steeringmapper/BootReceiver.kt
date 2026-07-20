package com.haval.h6.steeringmapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启动接收器
 * 车机重启后自动引导用户重新启用无障碍服务（如果之前已开启）
 *
 * 注意：AccessibilityService 在设备重启后需要用户手动重新开启（Android 系统安全限制）
 * 此处只能发送通知提醒用户，无法自动重启 AccessibilityService
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // 发送通知提醒用户重新开启无障碍服务
                // 实际通知实现需要 NotificationManager，此处简化
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 车机开机后可选择自动跳转到 App 提醒用户
                // context.startActivity(launchIntent)
            }
        }
    }
}