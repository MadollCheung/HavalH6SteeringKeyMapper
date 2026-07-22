package com.haval.h6.steeringmapper

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 按键映射数据模型
 */
data class KeyMapping(
    val id: String,                   // 唯一ID
    val modifierKeyCode: Int,         // 修饰键（默认 * 键）
    val actionKeyCode: Int,           // 功能键
    val action: HvacAction,           // 目标动作
    val description: String = "",     // 用户备注
    val enabled: Boolean = true       // 是否启用
) {
    fun getDisplayName(): String {
        val modName = keyCodeToFriendlyName(modifierKeyCode)
        val actName = keyCodeToFriendlyName(actionKeyCode)
        return "$modName + $actName → ${action.displayName}"
    }

    companion object {
        /** 将 keyCode 转换为可读名称，对车机自定义 keyCode 做特殊处理 */
        fun keyCodeToFriendlyName(keyCode: Int): String = when (keyCode) {
            189  -> "*"                   // 哈弗H6三代方向盘 * 键
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
    }
}

/**
 * 按键映射管理器
 * 负责映射表的增删改查和持久化存储（SharedPreferences + JSON）
 */
class KeyMappingManager(context: Context) {

    companion object {
        private const val PREF_NAME = "key_mappings"
        private const val KEY_MAPPINGS = "mappings"
        private const val KEY_STAR_KEYCODE = "star_keycode"
        // 哈弗H6三代方向盘 * 键实际 keyCode（adayo 车机上报值）
        private const val DEFAULT_STAR_KEYCODE = 189

        /**
         * 默认按键映射表（哈弗H6三代推荐配置）
         */
        fun getDefaultMappings(): List<KeyMapping> = listOf(
            KeyMapping(
                id = "default_01",
                modifierKeyCode = DEFAULT_STAR_KEYCODE,
                actionKeyCode = KeyEvent.KEYCODE_DPAD_UP,        // * + ▲
                action = HvacAction.TEMP_UP,
                description = "* + 上键 → 温度+"
            ),
            KeyMapping(
                id = "default_02",
                modifierKeyCode = DEFAULT_STAR_KEYCODE,
                actionKeyCode = KeyEvent.KEYCODE_DPAD_DOWN,      // * + ▼
                action = HvacAction.TEMP_DOWN,
                description = "* + 下键 → 温度-"
            ),
            KeyMapping(
                id = "default_03",
                modifierKeyCode = DEFAULT_STAR_KEYCODE,
                actionKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT,     // * + ▶
                action = HvacAction.FAN_SPEED_UP,
                description = "* + 右键 → 风量+"
            ),
            KeyMapping(
                id = "default_04",
                modifierKeyCode = DEFAULT_STAR_KEYCODE,
                actionKeyCode = KeyEvent.KEYCODE_DPAD_LEFT,      // * + ◀
                action = HvacAction.FAN_SPEED_DOWN,
                description = "* + 左键 → 风量-"
            ),
            KeyMapping(
                id = "default_05",
                modifierKeyCode = DEFAULT_STAR_KEYCODE,
                actionKeyCode = KeyEvent.KEYCODE_DPAD_CENTER,    // * + OK
                action = HvacAction.RECIRCULATION_TOGGLE,
                description = "* + 确认键 → 内外循环切换"
            ),
            KeyMapping(
                id = "default_06",
                modifierKeyCode = DEFAULT_STAR_KEYCODE,
                actionKeyCode = KeyEvent.KEYCODE_VOLUME_UP,      // * + 音量+
                action = HvacAction.AC_TOGGLE,
                description = "* + 音量+ → A/C 开关"
            ),
            KeyMapping(
                id = "default_07",
                modifierKeyCode = DEFAULT_STAR_KEYCODE,
                actionKeyCode = KeyEvent.KEYCODE_VOLUME_DOWN,    // * + 音量-
                action = HvacAction.AUTO_MODE,
                description = "* + 音量- → AUTO 自动模式"
            ),
            KeyMapping(
                id = "default_08",
                modifierKeyCode = DEFAULT_STAR_KEYCODE,
                actionKeyCode = KeyEvent.KEYCODE_CALL,           // * + 接听
                action = HvacAction.FRONT_DEFROST,
                description = "* + 接听键 → 前挡风除雾"
            ),
            KeyMapping(
                id = "default_09",
                modifierKeyCode = DEFAULT_STAR_KEYCODE,
                actionKeyCode = KeyEvent.KEYCODE_ENDCALL,        // * + 挂断
                action = HvacAction.REAR_DEFROST,
                description = "* + 挂断键 → 后挡风加热"
            )
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // 内存缓存
    private val mappings: MutableList<KeyMapping> = mutableListOf()

    init {
        loadMappings()
    }

    // ======================== 查询 ========================

    /**
     * 根据组合键查找映射
     */
    fun findMapping(modifierKeyCode: Int, actionKeyCode: Int): KeyMapping? {
        return mappings.find {
            it.enabled &&
                    it.modifierKeyCode == modifierKeyCode &&
                    it.actionKeyCode == actionKeyCode
        }
    }

    fun getAllMappings(): List<KeyMapping> = mappings.toList()

    fun getMappingById(id: String): KeyMapping? = mappings.find { it.id == id }

    /**
     * 获取 * 键的实际 KeyCode（用户可自定义，默认 KEYCODE_STAR）
     */
    fun getStarKeyCode(): Int {
        return prefs.getInt(KEY_STAR_KEYCODE, DEFAULT_STAR_KEYCODE)
    }

    // ======================== 修改 ========================

    fun addMapping(mapping: KeyMapping) {
        mappings.removeAll { it.id == mapping.id }
        mappings.add(mapping)
        saveMappings()
    }

    fun updateMapping(mapping: KeyMapping) {
        val index = mappings.indexOfFirst { it.id == mapping.id }
        if (index >= 0) {
            mappings[index] = mapping
            saveMappings()
        }
    }

    fun deleteMapping(id: String) {
        mappings.removeAll { it.id == id }
        saveMappings()
    }

    fun setStarKeyCode(keyCode: Int) {
        prefs.edit().putInt(KEY_STAR_KEYCODE, keyCode).apply()
    }

    /**
     * 重置为默认映射
     */
    fun resetToDefault() {
        mappings.clear()
        mappings.addAll(getDefaultMappings())
        saveMappings()
    }

    // ======================== 持久化 ========================

    private fun saveMappings() {
        val json = gson.toJson(mappings)
        prefs.edit().putString(KEY_MAPPINGS, json).apply()
    }

    private fun loadMappings() {
        val json = prefs.getString(KEY_MAPPINGS, null)
        if (json.isNullOrEmpty()) {
            // 首次启动，加载默认映射
            mappings.addAll(getDefaultMappings())
            saveMappings()
        } else {
            val type = object : TypeToken<List<KeyMapping>>() {}.type
            val loaded: List<KeyMapping> = gson.fromJson(json, type)
            mappings.addAll(loaded)
        }
    }
}
