package com.haval.h6.steeringmapper

/**
 * 空调动作枚举
 * 对应哈弗H6三代车机的空调控制功能
 */
enum class HvacAction(val displayName: String, val description: String) {
    TEMP_UP("温度+", "升高温度 0.5°C"),
    TEMP_DOWN("温度-", "降低温度 0.5°C"),
    FAN_SPEED_UP("风量+", "增加一档风量"),
    FAN_SPEED_DOWN("风量-", "减少一档风量"),
    RECIRCULATION_TOGGLE("内外循环切换", "切换内循环/外循环"),
    AC_TOGGLE("A/C 开关", "开启/关闭压缩机制冷"),
    AUTO_MODE("AUTO 自动模式", "切换到自动空调模式"),
    FRONT_DEFROST("前挡风除雾", "开启/关闭前挡风玻璃除雾"),
    REAR_DEFROST("后挡风除雾", "开启/关闭后挡风玻璃加热"),
    HVAC_POWER_TOGGLE("空调电源", "开启/关闭整个空调系统"),
    BLOWER_DIR_FACE("吹面", "风向调整为吹脸"),
    BLOWER_DIR_FEET("吹脚", "风向调整为吹脚"),
    BLOWER_DIR_BOTH("吹面+吹脚", "风向调整为吹脸+吹脚");

    companion object {
        fun fromName(name: String): HvacAction? = values().find { it.name == name }
    }
}